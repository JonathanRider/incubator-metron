/**
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
package org.apache.metron.common.dsl.functions.resolver;

import org.apache.metron.common.dsl.Context;
import org.apache.metron.common.dsl.StellarFunction;
import org.apache.metron.common.dsl.StellarFunctionInfo;

import java.util.function.Function;

/**
 * Responsible for function resolution in Stellar.
 */
public interface FunctionResolver extends Function<String, StellarFunction> {

  /**
   * Provides metadata about each Stellar function that is resolvable.
   */
  Iterable<StellarFunctionInfo> getFunctionInfo();

  /**
   * The names of all Stellar functions that are resolvable.
   */
  Iterable<String> getFunctions();

  /**
   * Initialize the function resolver.
   * @param context Context used to initialize.
   */
  void initialize(Context context);
}
