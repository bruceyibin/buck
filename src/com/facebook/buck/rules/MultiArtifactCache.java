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

package com.facebook.buck.rules;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;

/**
 * MultiArtifactCache encapsulates a set of ArtifactCache instances such that fetch() succeeds if
 * any of the ArtifactCaches contain the desired artifact, and store() applies to all
 * ArtifactCaches.
 */
public class MultiArtifactCache implements ArtifactCache {
  private final ImmutableList<ArtifactCache> artifactCaches;

  public MultiArtifactCache(ImmutableList<ArtifactCache> artifactCaches) {
    this.artifactCaches = Preconditions.checkNotNull(artifactCaches);
  }

  /**
   * Fetch the artifact matching ruleKey and store it to output. If any of the encapsulated
   * ArtifactCaches contains the desired artifact, this method succeeds, and it may store the
   * artifact to one or more of the other encapsulated ArtifactCaches as a side effect.
   */
  @Override
  public boolean fetch(RuleKey ruleKey, File output) {
    for (ArtifactCache artifactCache : artifactCaches) {
      if (artifactCache.fetch(ruleKey, output)) {
        // Success; terminate search for a cached artifact, and propagate artifact to caches
        // earlier in the search order so that subsequent searches terminate earlier.
        for (ArtifactCache priorArtifactCache : artifactCaches) {
          if (priorArtifactCache.equals(artifactCache)) {
            break;
          }
          priorArtifactCache.store(ruleKey, output);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Store the artifact to all encapsulated ArtifactCaches.
   */
  @Override
  public void store(RuleKey ruleKey, File output) {
    for (ArtifactCache artifactCache : artifactCaches) {
      artifactCache.store(ruleKey, output);
    }
  }
}
