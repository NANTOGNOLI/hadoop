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

package org.apache.hadoop.fs.s3a.impl;

import java.util.Objects;

import org.apache.hadoop.service.AbstractService;

public abstract class AbstractS3AService
    extends AbstractService
    implements S3AService {

  private StoreContext storeContext;

  protected AbstractS3AService(final String name) {
    super(name);
  }

  @Override
  public void setStoreContext(final StoreContext storeContext) {
    this.storeContext = storeContext;
  }

  @Override
  public StoreContext getStoreContext() {
    return storeContext;
  }

  /**
   * Validate the state of the service, then start the service.
   * Service start may be async.
   * @throws Exception if initialization failed.
   */
  @Override
  protected void serviceStart() throws Exception {
    Objects.requireNonNull(storeContext, () ->
        "not initialized with store context: " + getName());
  }
}
