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

package org.apache.hadoop.service.launcher;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.launcher.testservices.ExceptionInExecuteLaunchedService;
import org.apache.hadoop.service.launcher.testservices.LaunchedRunningService;
import org.apache.hadoop.service.launcher.testservices.NoArgsAllowedService;
import org.junit.Test;

/**
 * Test the inner launcher methods. 
 */
public class TestServiceLauncherInnerMethods extends
    AbstractServiceLauncherTestBase {

  @Test
  public void testLaunchService() throws Throwable {
    ServiceLauncher<NoArgsAllowedService> launcher =
        launchService(NoArgsAllowedService.class, new Configuration());
  }

  @Test
  public void testLaunchServiceArgs() throws Throwable {
    launchExpectingException(NoArgsAllowedService.class,
        new Configuration(),
        "arguments", EXIT_COMMAND_ARGUMENT_ERROR,
        "one", "two");
  }

  @Test
  public void testAccessLaunchedService() throws Throwable {
    ServiceLauncher<LaunchedRunningService> launcher =
        launchService(LaunchedRunningService.class, new Configuration());
    LaunchedRunningService service = launcher.getService();
    service.failInRun = true;
    service.exitCode = EXIT_CONNECTIVITY_PROBLEM;
    assertEquals(EXIT_CONNECTIVITY_PROBLEM, service.execute());
  }

  @Test
  public void testLaunchThrowableRaised() throws Throwable {
    launchExpectingException(ExceptionInExecuteLaunchedService.class,
        new Configuration(),
        "java.lang.OutOfMemoryError", EXIT_EXCEPTION_THROWN,
        ExceptionInExecuteLaunchedService.ARG_THROWABLE);
  }

}
