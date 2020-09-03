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

package org.apache.hadoop.fs.s3a.performance;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Callable;

import org.assertj.core.api.Assertions;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataOutputStreamBuilder;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.apache.hadoop.fs.s3a.AbstractS3ATestBase;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.Statistic;
import org.apache.hadoop.fs.s3a.Tristate;
import org.apache.hadoop.fs.s3a.impl.StatusProbeEnum;

import static org.apache.hadoop.fs.s3a.Constants.*;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.*;
import static org.apache.hadoop.fs.s3a.Statistic.*;
import static org.apache.hadoop.fs.s3a.performance.OperationCost.*;
import static org.apache.hadoop.fs.s3a.performance.OperationCostValidator.expect;
import static org.apache.hadoop.fs.s3a.performance.OperationCostValidator.probe;
import static org.apache.hadoop.test.AssertExtensions.dynamicDescription;

/**
 * Abstract class for tests which make assertions about cost.
 * <p></p>
 * Factored out from {@code ITestS3AFileOperationCost}
 */
public class AbstractS3ACostTest extends AbstractS3ATestBase {

  /**
   * Parameter: should the stores be guarded?
   */
  private final boolean s3guard;

  /**
   * Parameter: should directory markers be retained?
   */
  private final boolean keepMarkers;

  /**
   * Is this an auth mode test run?
   */
  private final boolean authoritative;

  /** probe states calculated from the configuration options. */
  private boolean isGuarded;

  private boolean isRaw;

  private boolean isAuthoritative;

  private boolean isNonAuth;

  private boolean isKeeping;

  private boolean isDeleting;

  private OperationCostValidator costValidator;

  public AbstractS3ACostTest(
      final boolean s3guard,
      final boolean keepMarkers,
      final boolean authoritative) {
    this.s3guard = s3guard;
    this.keepMarkers = keepMarkers;
    this.authoritative = authoritative;
  }

  @Override
  public Configuration createConfiguration() {
    Configuration conf = super.createConfiguration();
    String bucketName = getTestBucketName(conf);
    removeBucketOverrides(bucketName, conf,
        S3_METADATA_STORE_IMPL);
    if (!isGuarded()) {
      // in a raw run remove all s3guard settings
      removeBaseAndBucketOverrides(bucketName, conf,
          S3_METADATA_STORE_IMPL);
    }
    removeBaseAndBucketOverrides(bucketName, conf,
        DIRECTORY_MARKER_POLICY,
        METADATASTORE_AUTHORITATIVE,
        AUTHORITATIVE_PATH);
    // directory marker options
    conf.set(DIRECTORY_MARKER_POLICY,
        keepMarkers
            ? DIRECTORY_MARKER_POLICY_KEEP
            : DIRECTORY_MARKER_POLICY_DELETE);
    conf.setBoolean(METADATASTORE_AUTHORITATIVE, authoritative);
    disableFilesystemCaching(conf);
    return conf;
  }

  @Override
  public void setup() throws Exception {
    super.setup();
    if (isGuarded()) {
      // s3guard is required for those test runs where any of the
      // guard options are set
      assumeS3GuardState(true, getConfiguration());
    }
    S3AFileSystem fs = getFileSystem();
    skipDuringFaultInjection(fs);

    // build up the states
    isGuarded = isGuarded();

    isRaw = !isGuarded;
    isAuthoritative = isGuarded && authoritative;
    isNonAuth = isGuarded && !authoritative;

    isKeeping = isKeepingMarkers();

    isDeleting = !isKeeping;

    // insert new metrics so as to keep the list sorted
    costValidator = OperationCostValidator.builder(getFileSystem())
        .withMetrics(
            DIRECTORIES_CREATED,
            DIRECTORIES_DELETED,
            FAKE_DIRECTORIES_DELETED,
            FILES_DELETED,
            INVOCATION_COPY_FROM_LOCAL_FILE,
            OBJECT_COPY_REQUESTS,
            OBJECT_DELETE_REQUESTS,
            OBJECT_LIST_REQUESTS,
            OBJECT_METADATA_REQUESTS,
            OBJECT_PUT_BYTES,
            OBJECT_PUT_REQUESTS,
            STREAM_OPENED,
            STREAM_CLOSE_BYTES_READ,
            STREAM_SEEK_BYTES_SKIPPED)
        .build();
  }

  public void assumeUnguarded() {
    assume("Unguarded FS only", !isGuarded());
  }

  /**
   * Is the store guarded authoritatively on the test path?
   * @return true if the condition is met on this test run.
   */
  public boolean isAuthoritative() {
    return authoritative;
  }

  /**
   * Is the store guarded?
   * @return true if the condition is met on this test run.
   */
  public boolean isGuarded() {
    return s3guard;
  }

  /**
   * Is the store raw?
   * @return true if the condition is met on this test run.
   */
  public boolean isRaw() {
    return isRaw;
  }

  /**
   * Is the store guarded non-authoritatively on the test path?
   * @return true if the condition is met on this test run.
   */
  public boolean isNonAuth() {
    return isNonAuth;
  }

  public boolean isDeleting() {
    return isDeleting;
  }

  public boolean isKeepingMarkers() {
    return keepMarkers;
  }

  /**
   * A special object whose toString() value is the current
   * state of the metrics.
   */
  protected Object getMetricSummary() {
    return costValidator;
  }

  /**
   * Create then close the file through the builder API.
   * @param path path
   * @param overwrite overwrite flag
   * @param recursive true == skip parent existence check
   * @param cost expected cost
   * @return path to new object.
   */
  protected Path buildFile(Path path,
      boolean overwrite,
      boolean recursive,
      OperationCost cost) throws Exception {
    resetStatistics();
    verifyRaw(cost, () -> {
      FSDataOutputStreamBuilder builder = getFileSystem().createFile(path)
          .overwrite(overwrite);
      if (recursive) {
        builder.recursive();
      }
      FSDataOutputStream stream = builder.build();
      stream.close();
      return stream.toString();
    });
    return path;
  }

  /**
   * Create a directory, returning its path.
   * @param p path to dir.
   * @return path of new dir
   */
  protected Path dir(Path p) throws IOException {
    mkdirs(p);
    return p;
  }

  /**
   * Create a file, returning its path.
   * @param p path to file.
   * @return path of new file
   */
  protected Path file(Path p) throws IOException {
    return file(p, true);
  }

  /**
   * Create a file, returning its path.
   * @param path path to file.
   * @param overwrite overwrite flag
   * @return path of new file
   */
  protected Path file(Path path, final boolean overwrite)
      throws IOException {
    getFileSystem().create(path, overwrite).close();
    return path;
  }

  /**
   * Touch a file, overwriting.
   * @param path path
   * @return path to new object.
   */
  protected Path create(Path path) throws Exception {
    return create(path, true, CREATE_FILE_OVERWRITE);
  }

  /**
   * Create then close the file.
   * @param path path
   * @param overwrite overwrite flag
   * @param cost expected cost

   * @return path to new object.
   */
  protected Path create(Path path, boolean overwrite,
      OperationCost cost) throws Exception {
    return verifyRaw(cost, () ->
        file(path, overwrite));
  }

  /**
   * Execute rename, returning the current metrics.
   * For use in l-expressions.
   * @param source source path.
   * @param dest dest path
   * @return a string for exceptions.
   */
  public String execRename(final Path source,
      final Path dest) throws IOException {
    getFileSystem().rename(source, dest);
    return String.format("rename(%s, %s): %s",
        dest, source, getMetricSummary());
  }

  /**
   * How many directories are in a path?
   * @param path path to probe.
   * @return the number of entries below root this path is
   */
  protected int directoriesInPath(Path path) {
    return path.isRoot() ? 0 : 1 + directoriesInPath(path.getParent());
  }

  /**
   * Reset all the metrics being tracked.
   */
  private void resetStatistics() {
    costValidator.resetMetricDiffs();
  }

  /**
   * Execute a closure and verify the metrics.
   * @param eval closure to evaluate
   * @param expected varargs list of expected diffs
   * @param <T> return type.
   * @return the result of the evaluation
   */
  protected <T> T verifyMetrics(
      Callable<T> eval,
      OperationCostValidator.ExpectedProbe... expected) throws Exception {
    return costValidator.exec(eval, expected);

  }

  /**
   * Execute a closure, expecting an exception.
   * Verify the metrics after the exception has been caught and
   * validated.
   * @param clazz type of exception
   * @param text text to look for in exception (optional)
   * @param eval closure to evaluate
   * @param expected varargs list of expected diffs
   * @param <T> return type of closure
   * @param <E> exception type
   * @return the exception caught.
   * @throws Exception any other exception
   */
  protected <T, E extends Throwable> E verifyMetricsIntercepting(
      Class<E> clazz,
      String text,
      Callable<T> eval,
      OperationCostValidator.ExpectedProbe... expected) throws Exception {
    return costValidator.intercepting(clazz, text, eval, expected);
  }

  /**
   * Execute a closure expecting an exception.
   * @param clazz type of exception
   * @param text text to look for in exception (optional)
   * @param head expected head request count.
   * @param list expected list request count.
   * @param eval closure to evaluate
   * @param <T> return type of closure
   * @param <E> exception type
   * @return the exception caught.
   * @throws Exception any other exception
   */
  protected <T, E extends Throwable> E interceptRaw(
      Class<E> clazz,
      String text,
      OperationCost cost,
      Callable<T> eval) throws Exception {
    return verifyMetricsIntercepting(clazz, text, eval, whenRaw(cost));
  }

  /**
   * Declare the expected cost on any FS.
   * @param cost costs to expect
   * @return a probe.
   */
  protected OperationCostValidator.ExpectedProbe always(
      OperationCost cost) {
    return expect(true, cost);
  }

  /**
   * Declare the expected cost on a raw FS.
   * @param cost costs to expect
   * @return a probe.
   */
  protected OperationCostValidator.ExpectedProbe whenRaw(
      OperationCost cost) {
    return expect(isRaw(), cost);
  }

  /**
   * Declare the expected cost on a guarded FS.
   * @param cost costs to expect
   * @return a probe.
   */
  protected OperationCostValidator.ExpectedProbe whenGuarded(
      OperationCost cost) {
    return expect(isGuarded(), cost);
  }

  /**
   * Declare the expected cost on a guarded auth FS.
   * @param cost costs to expect
   * @return a probe.
   */
  protected OperationCostValidator.ExpectedProbe whenAuthoritative(
      OperationCost cost) {
    return expect(isAuthoritative(), cost);
  }


  /**
   * Declare the expected cost on a guarded nonauth FS.
   * @param cost costs to expect
   * @return a probe.
   */
  protected OperationCostValidator.ExpectedProbe whenNonauth(
      OperationCost cost) {
    return expect(isNonAuth(), cost);
  }


  /**
   * A metric diff which must hold when the fs is keeping markers.
   * @param cost expected cost
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe whenKeeping(
      OperationCost cost) {
    return expect(isKeepingMarkers(), cost);
  }

  /**
   * A metric diff which must hold when the fs is keeping markers.
   * @param cost expected cost
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe whenDeleting(
      OperationCost cost) {
    return expect(isDeleting(), cost);
  }

  /**
   * Execute a closure expecting a specific number of HEAD/LIST calls
   * on <i>raw</i> S3 stores only.
   * @param cost expected cost
   * @param eval closure to evaluate
   * @param <T> return type of closure
   * @return the result of the evaluation
   */
  protected <T> T verifyRaw(
      OperationCost cost,
      Callable<T> eval) throws Exception {
    return verifyMetrics(eval, whenRaw(cost));
  }

  /**
   * Execute {@code S3AFileSystem#innerGetFileStatus(Path, boolean, Set)}
   * for the given probes.
   * expect the specific HEAD/LIST count with a raw FS.
   * @param path path
   * @param needEmptyDirectoryFlag look for empty directory
   * @param probes file status probes to perform
   * @param cost expected cost
   * @return the status
   */
  public S3AFileStatus verifyRawInnerGetFileStatus(
      Path path,
      boolean needEmptyDirectoryFlag,
      Set<StatusProbeEnum> probes,
      OperationCost cost) throws Exception {
    return verifyRaw(cost, () ->
        innerGetFileStatus(getFileSystem(),
            path,
            needEmptyDirectoryFlag,
            probes));
  }

  /**
   * Execute {@code S3AFileSystem#innerGetFileStatus(Path, boolean, Set)}
   * for the given probes -expect a FileNotFoundException,
   * and the specific HEAD/LIST count with a raw FS.
   * @param path path
   * @param needEmptyDirectoryFlag look for empty directory
   * @param probes file status probes to perform
   * @param cost expected cost
   */

  public void interceptRawGetFileStatusFNFE(
      Path path,
      boolean needEmptyDirectoryFlag,
      Set<StatusProbeEnum> probes,
      OperationCost cost) throws Exception {
    interceptRaw(FileNotFoundException.class, "",
        cost, () ->
            innerGetFileStatus(getFileSystem(),
                path,
                needEmptyDirectoryFlag,
                probes));
  }

  /**
   * Probe for a path being a directory.
   * Metrics are only checked on unguarded stores.
   * @param path path
   * @param expected expected outcome
   * @param cost expected cost on a Raw FS.
   */
  protected void isDir(Path path,
      boolean expected,
      OperationCost cost) throws Exception {
    boolean b = verifyRaw(cost, () ->
        getFileSystem().isDirectory(path));
    Assertions.assertThat(b)
        .describedAs("isDirectory(%s)", path)
        .isEqualTo(expected);
  }

  /**
   * Probe for a path being a file.
   * Metrics are only checked on unguarded stores.
   * @param path path
   * @param expected expected outcome
   * @param cost expected cost on a Raw FS.
   */
  protected void isFile(Path path,
      boolean expected,
      OperationCost cost) throws Exception {
    boolean b = verifyRaw(cost, () ->
        getFileSystem().isFile(path));
    Assertions.assertThat(b)
        .describedAs("isFile(%s)", path)
        .isEqualTo(expected);
  }

  /**
   * A metric diff which must always hold.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe with(
      final Statistic stat, final int expected) {
    return probe(stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is unguarded.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenRaw(
      final Statistic stat, final int expected) {
    return probe(isRaw(), stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is guarded.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenGuarded(
      final Statistic stat,
      final int expected) {
    return probe(isGuarded(), stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is guarded + authoritative.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenAuthoritative(
      final Statistic stat,
      final int expected) {
    return probe(isAuthoritative(), stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is guarded + authoritative.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenNonauth(
      final Statistic stat,
      final int expected) {
    return probe(isNonAuth(), stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is keeping markers.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenKeeping(
      final Statistic stat,
      final int expected) {
    return probe(isKeepingMarkers(), stat, expected);
  }

  /**
   * A metric diff which must hold when the fs is keeping markers.
   * @param stat metric source
   * @param expected expected value.
   * @return the diff.
   */
  protected OperationCostValidator.ExpectedProbe withWhenDeleting(
      final Statistic stat,
      final int expected) {
    return probe(isDeleting(), stat, expected);
  }

  /**
   * Assert the empty directory status of a file is as expected.
   * The raised assertion message includes a list of the path.
   * @param status status to probe.
   * @param expected expected value
   */
  protected void assertEmptyDirStatus(final S3AFileStatus status,
      final Tristate expected) {
    Assertions.assertThat(status.isEmptyDirectory())
        .describedAs(dynamicDescription(() ->
            "FileStatus says directory is not empty: " + status
                + "\n" + ContractTestUtils.ls(
                    getFileSystem(), status.getPath())))
        .isEqualTo(expected);
  }
}
