/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class Parser {

  private final BuildTargetParser buildTargetParser;

  /**
   * The build files that have been parsed and whose build rules are in {@link #knownBuildTargets}.
   */
  private final ListMultimap<File, Map<String, Object>> parsedBuildFiles;

  /**
   * True if all build files have been parsed and so all rules are in {@link #knownBuildTargets}.
   */
  private boolean allBuildFilesParsed;

  /**
   * Files included by build files. Changing includes invalidates cached build rules.
   */
  @Nullable
  private List<String> includes;

  /**
   * We parse a build file in search for one particular rule; however, we also keep track of the
   * other rules that were also parsed from it.
   */
  private final Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets;

  private final String absolutePathToProjectRoot;

  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final ProjectBuildFileParser buildFileParser;
  private final Console console;
  private BuildFileTree buildFiles;

  public Parser(ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      Console console) {
    this(projectFilesystem,
        buildRuleTypes,
        console,
        BuildFileTree.constructBuildFileTree(projectFilesystem),
        new BuildTargetParser(projectFilesystem),
         /* knownBuildTargets */ Maps.<BuildTarget, BuildRuleBuilder<?>>newHashMap(),
        new ProjectBuildFileParser(projectFilesystem.getIgnorePaths()));
  }

  @VisibleForTesting
  Parser(ProjectFilesystem projectFilesystem,
         KnownBuildRuleTypes buildRuleTypes,
         Console console,
         BuildFileTree buildFiles,
         BuildTargetParser buildTargetParser,
         Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets,
         ProjectBuildFileParser buildFileParser) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.console = Preconditions.checkNotNull(console);
    this.buildFiles = Preconditions.checkNotNull(buildFiles);
    this.knownBuildTargets = Maps.newHashMap(Preconditions.checkNotNull(knownBuildTargets));
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
    this.buildFileParser = Preconditions.checkNotNull(buildFileParser);
    this.parsedBuildFiles = ArrayListMultimap.create();
    this.absolutePathToProjectRoot = projectFilesystem.getProjectRoot().getAbsolutePath();
  }

  public BuildTargetParser getBuildTargetParser() {
    return buildTargetParser;
  }

  public File getProjectRoot() {
    return projectFilesystem.getProjectRoot();
  }

  /**
   * @param file the build file to look up in the {@link #parsedBuildFiles} cache.
   * @param includes the files to include before executing the build file.
   * @return true if the build file has already been parsed and its rules are cached.
   */
  private boolean isCached(File file, Iterable<String> includes) {
    return isCacheValid(includes) && (allBuildFilesParsed || parsedBuildFiles.containsKey(file));
  }

  /**
   * @param includes the files to include before executing the build file.
   * @return true if all build files have already been parsed and their rules are cached.
   */
  private boolean isCacheComplete(Iterable<String> includes) {
    return isCacheValid(includes) && allBuildFilesParsed;
  }

  /**
   * @param includes the files to include before executing the build file.
   * @return true if the cached build rules are valid. Invalidates the cache if not.
   */
  private boolean isCacheValid(Iterable<String> includes) {
    List<String> includesList = Lists.newArrayList(includes);
    if (!includesList.equals(this.includes)) {
      invalidateCache();
      this.includes = includesList;
      return false;
    }
    return true;
  }

  private void invalidateCache() {
    buildFiles = BuildFileTree.constructBuildFileTree(projectFilesystem);
    parsedBuildFiles.clear();
    knownBuildTargets.clear();
    allBuildFilesParsed = false;
  }

  /**
   * @param buildTargets the build targets to generate a dependency graph for.
   * @param defaultIncludes the files to include before executing build files.
   * @return the dependency graph containing the build targets and their related targets.
   */
  public DependencyGraph parseBuildFilesForTargets(
      Iterable<BuildTarget> buildTargets,
      Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    // TODO(royw): Thread through the EventBus and log parseStart/parseEnd events.
    // Make sure that knownBuildTargets is initially populated with the BuildRuleBuilders for the
    // seed BuildTargets for the traversal.
    if (!isCacheComplete(defaultIncludes)) {
      Set<File> buildTargetFiles = Sets.newHashSet();
      for (BuildTarget buildTarget : buildTargets) {
        File buildFile = buildTarget.getBuildFile();
        boolean isNewElement = buildTargetFiles.add(buildFile);
        if (isNewElement) {
          parseBuildFile(buildFile, defaultIncludes);
        }
      }
    }

    DependencyGraph graph = findAllTransitiveDependencies(buildTargets, defaultIncludes);
    return graph;
  }

  /**
   * @param toExplore BuildTargets whose dependencies need to be explored.
   */
  @VisibleForTesting
  DependencyGraph findAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes) {
    final BuildRuleResolver ruleResolver = new BuildRuleResolver();
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();

    AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {
          @Override
          protected Iterator<BuildTarget> findChildren(BuildTarget buildTarget) {
            ParseContext parseContext = ParseContext.forBaseName(buildTarget.getBaseName());

            // Verify that the BuildTarget actually exists in the map of known BuildTargets
            // before trying to recurse through its children.
            if (!knownBuildTargets.containsKey(buildTarget)) {
              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(buildTarget, parseContext));
            }

            BuildRuleBuilder<?> buildRuleBuilder = knownBuildTargets.get(buildTarget);

            Set<BuildTarget> deps = Sets.newHashSet();
            for (BuildTarget buildTargetForDep : buildRuleBuilder.getDeps()) {
              try {
                if (!knownBuildTargets.containsKey(buildTargetForDep)) {
                  parseBuildFileContainingTarget(buildTargetForDep, defaultIncludes);
                }
                deps.add(buildTargetForDep);
              } catch (NoSuchBuildTargetException e) {
                throw new HumanReadableException(e);
              } catch (IOException e) {
                Throwables.propagate(e);
              }
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildTarget buildTarget) {
            BuildRuleBuilder<?> builderForTarget = knownBuildTargets.get(buildTarget);
            BuildRule buildRule = ruleResolver.buildAndAddToIndex(builderForTarget);

            // Update the graph.
            if (buildRule.getDeps().isEmpty()) {
              // If a build rule with no deps is specified as the build target to build, then make
              // sure it is in the graph.
              graph.addNode(buildRule);
            } else {
              for (BuildRule dep : buildRule.getDeps()) {
                graph.addEdge(buildRule, dep);
              }
            }
          }

          @Override
          protected void onTraversalComplete(
              Iterable<BuildTarget> nodesInExplorationOrder) {
          }
    };

    try {
      traversal.traverse(toExplore);
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    }

    return new DependencyGraph(graph);
  }

  /**
   * Note that if this Parser is populated via
   * {@link #filterAllTargetsInProject}, then this method should not be called.
   */
  private void parseBuildFileContainingTarget(
      BuildTarget buildTarget, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    if (isCacheComplete(defaultIncludes)) {
      // In this case, all of the build rules should have been loaded into the knownBuildTargets
      // Map before this method was invoked. Therefore, there should not be any more build files to
      // parse. This must be the result of traversing a non-existent dep in a build rule, so an
      // error is reported to the user. Unfortunately, the source of the build file where the
      // non-existent rule was declared is not known at this point, which is why it is not included
      // in the error message.
      throw new HumanReadableException("No such build target: %s.", buildTarget);
    }

    File buildFile = buildTarget.getBuildFile();
    if (isCached(buildFile, defaultIncludes)) {
      throw new HumanReadableException(
          "The build file that should contain %s has already been parsed (%s), " +
              "but %s was not found. Please make sure that %s is defined in %s.",
          buildTarget,
          buildFile,
          buildTarget,
          buildTarget,
          buildFile);
    }

    parseBuildFile(buildFile, defaultIncludes);
  }

  /**
   * @param buildFile the build file to execute to generate build rules if they are not cached.
   * @param defaultIncludes the files to include before executing the build file.
   * @return a list of raw build rules generated by executing the build file.
   */
  public List<Map<String,Object>> parseBuildFile(File buildFile, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    Preconditions.checkNotNull(buildFile);
    Preconditions.checkNotNull(defaultIncludes);
    if (!isCached(buildFile, defaultIncludes)) {
      if (console.getVerbosity().shouldPrintCommand()) {
        console.getStdErr().printf("Parsing %s file: %s\n",
            BuckConstant.BUILD_RULES_FILE_NAME,
            buildFile);
      }

      parseRawRulesInternal(buildFileParser.getAllRules(
          absolutePathToProjectRoot, Optional.of(buildFile.getPath()), defaultIncludes),
          buildFile);
    }
    return parsedBuildFiles.get(buildFile);
  }

  /**
   * @param rules the raw rule objects to parse.
   * @param source the build file the rules were read from, or null if all build files were read.
   */
  @VisibleForTesting
  void parseRawRulesInternal(Iterable<Map<String, Object>> rules,
      @Nullable File source) throws NoSuchBuildTargetException {
    for (Map<String, Object> map : rules) {
      BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
      BuildTarget target = parseBuildTargetFromRawRule(map, source);
      BuildRuleFactory<?> factory = buildRuleTypes.getFactory(buildRuleType);
      if (factory == null) {
        throw new HumanReadableException("Unrecognized rule %s while parsing %s.",
            buildRuleType,
            target.getBuildFile());
      }

      BuildRuleBuilder<?> buildRuleBuilder = factory.newInstance(new BuildRuleFactoryParams(
          map,
          console,
          projectFilesystem,
          buildFiles,
          buildTargetParser,
          target));
      Object existingRule = knownBuildTargets.put(target, buildRuleBuilder);
      if (existingRule != null) {
        throw new RuntimeException("Duplicate definition for " + target.getFullyQualifiedName());
      }
      parsedBuildFiles.put(target.getBuildFile(), map);
    }
  }

  /**
   * @param filter the test to apply to all targets that have been read from build files, or null.
   * @return the build targets that pass the test, or null if the filter was null.
   */
  @VisibleForTesting
  @Nullable
  List<BuildTarget> filterTargets(@Nullable RawRulePredicate filter)
      throws NoSuchBuildTargetException {
    if (filter == null) {
      return null;
    }

    List<BuildTarget> matchingTargets = Lists.newArrayList();
    for (Map<String, Object> map : parsedBuildFiles.values()) {
      BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
      BuildTarget target = parseBuildTargetFromRawRule(map, null);
      if (filter.isMatch(map, buildRuleType, target)) {
        matchingTargets.add(target);
      }
    }

    return matchingTargets;
  }

  /**
   * @param map the map of values that define the rule.
   * @return the type of rule defined by the map.
   */
  private BuildRuleType parseBuildRuleTypeFromRawRule(Map<String, Object> map) {
    String type = (String)map.get("type");
    return buildRuleTypes.getBuildRuleType(type);
  }

  /**
   * @param map the map of values that define the rule.
   * @param source the build file the map was read from, or null if all build files were read.
   * @return the build target defined by the rule.
   */
  private BuildTarget parseBuildTargetFromRawRule(Map<String, Object> map, @Nullable File source) {
    String basePath = (String)map.get("buck_base_path");
    File sourceOfBuildTarget;
    if (source == null) {
      String relativePathToBuildFile = !basePath.isEmpty()
          ? basePath + "/" + BuckConstant.BUILD_RULES_FILE_NAME
          : BuckConstant.BUILD_RULES_FILE_NAME;
      sourceOfBuildTarget = new File(projectFilesystem.getProjectRoot(), relativePathToBuildFile);
    } else {
      sourceOfBuildTarget = source;
    }
    String name = (String)map.get("name");
    return new BuildTarget(sourceOfBuildTarget, "//" + basePath, name);
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct a
   * dependency graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @param filesystem The project filesystem.
   * @param includes A list of files that should be included by each build file.
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   * @return The build targets in the project filtered by the given filter.
   */
  public List<BuildTarget> filterAllTargetsInProject(ProjectFilesystem filesystem,
                                                     Iterable<String> includes,
                                                     @Nullable RawRulePredicate filter)
      throws IOException, NoSuchBuildTargetException {
    Preconditions.checkNotNull(filesystem);
    Preconditions.checkNotNull(includes);
    if (!projectFilesystem.getProjectRoot().equals(filesystem.getProjectRoot())) {
      throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
          projectFilesystem.getProjectRoot(), filesystem.getProjectRoot()));
    }
    if (!isCacheComplete(includes)) {
      knownBuildTargets.clear();
      parsedBuildFiles.clear();
      parseRawRulesInternal(
          buildFileParser.getAllRulesInProject(filesystem.getProjectRoot(), includes),
          null /* source */);
      allBuildFilesParsed = true;
    }
    return filterTargets(filter);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchEvent<?> event) {
    if (projectFilesystem.isPathChangeEvent(event)) {
      // TODO(user): Track the files imported by build files
      // Currently we just assume changed ".java" can't affect build rules.
      // Adding or deleting "*.java" files requires build files to be reevaluated due to globing.
      final String SRC_EXTENSION = ".java";
      Path path = (Path) event.context();
      if (path.toString().endsWith(SRC_EXTENSION)
          && event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
        return;
      }
    }
    // TODO(user): invalidate affected build files, rather than nuking all rules completely.
    invalidateCache();
  }
}
