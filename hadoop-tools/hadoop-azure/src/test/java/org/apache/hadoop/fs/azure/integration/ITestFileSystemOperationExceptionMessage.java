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

package org.apache.hadoop.fs.azure.integration;

import java.net.URI;
import java.util.UUID;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.fs.azure.AzureBlobStorageTestAccount;
import org.apache.hadoop.fs.azure.AzureException;
import org.apache.hadoop.fs.azure.NativeAzureFileSystem;
import org.apache.hadoop.test.GenericTestUtils;

import org.junit.Assert;
import org.junit.Test;

import static org.apache.hadoop.fs.azure.AzureNativeFileSystemStore.NO_ACCESS_TO_CONTAINER_MSG;

/**
 * Test for error messages coming from SDK.
 */
public class ITestFileSystemOperationExceptionMessage
    extends AbstractAzureIntegrationTest {

  @Test
  public void testAnonymouseCredentialExceptionMessage() throws Throwable {

    Configuration conf = AzureBlobStorageTestAccount.createTestConfiguration();
    String testStorageAccount = conf.get("fs.azure.test.account.name");
    conf = new Configuration();
    conf.set("fs.AbstractFileSystem.wasb.impl",
        "org.apache.hadoop.fs.azure.Wasb");
    conf.set("fs.azure.skip.metrics", "true");

    String testContainer = UUID.randomUUID().toString();
    String wasbUri = String.format("wasb://%s@%s",
        testContainer, testStorageAccount);

    try(NativeAzureFileSystem fs = new NativeAzureFileSystem()) {
      fs.initialize(new URI(wasbUri), conf);
      fail("Expected an exception, got " + fs);
    } catch (Exception ex) {

      Throwable innerException = ex.getCause();
      while (innerException != null
          && !(innerException instanceof AzureException)) {
        innerException = innerException.getCause();
      }

      if (innerException != null) {
        GenericTestUtils.assertExceptionContains(String.format(
            NO_ACCESS_TO_CONTAINER_MSG, testStorageAccount, testContainer),
            ex);
      } else {
        Assert.fail("No inner azure exception");
      }
    }
  }
}
