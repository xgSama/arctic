/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.utils.map;

import com.netease.arctic.iceberg.optimize.StructLikeWrapper;
import com.netease.arctic.utils.SerializationUtils;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Types;

import java.util.HashMap;

/**
 * Copy form iceberg {@link org.apache.iceberg.util.StructLikeMap}. Make using StructLikeWrapper more cheap
 */
public class StructLikeSpillableMap<T> extends StructLikeBaseMap<T> {

  public static <T> StructLikeSpillableMap<T> create(Types.StructType type,
                                                     Long maxInMemorySizeInBytes) {
    return new StructLikeSpillableMap<>(type, maxInMemorySizeInBytes);
  }

  private final SimpleMap<StructLikeWrapper, T> wrapperMap;

  private StructLikeSpillableMap(Types.StructType type, Long maxInMemorySizeInBytes) {
    super(type);
    this.wrapperMap = new SimpleSpillableMap(maxInMemorySizeInBytes,
        SerializationUtils.createStructLikeWrapperSerializer(structLikeWrapperFactory),
        SerializationUtils.createJavaSimpleSerializer());
  }

  @Override
  protected SimpleMap<StructLikeWrapper, T> getInternalMap() {
    return wrapperMap;
  }
}

