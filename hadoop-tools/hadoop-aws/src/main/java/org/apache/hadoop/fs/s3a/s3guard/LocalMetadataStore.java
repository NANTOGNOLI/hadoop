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

package org.apache.hadoop.fs.s3a.s3guard;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.Tristate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This is a local, in-memory, implementation of MetadataStore.
 * This is <i>not</i> a coherent cache across processes.  It is only
 * locally-coherent.
 *
 * The purpose of this is for unit and integration testing.
 * It could also be used to accelerate local-only operations where only one
 * process is operating on a given object store, or multiple processes are
 * accessing a read-only storage bucket.
 *
 * This MetadataStore does not enforce filesystem rules such as disallowing
 * non-recursive removal of non-empty directories.  It is assumed the caller
 * already has to perform these sorts of checks.
 *
 * Contains cache internally with time based eviction.
 */
public class LocalMetadataStore implements MetadataStore {

  public static final Logger LOG = LoggerFactory.getLogger(MetadataStore.class);
  public static final int DEFAULT_MAX_RECORDS = 128;
  public static final int DEFAULT_CACHE_ENTRY_TTL_MSEC = 10 * 1000;

  /**
   * Maximum number of records.
   */
  @InterfaceStability.Evolving
  public static final String CONF_MAX_RECORDS =
      "fs.metadatastore.local.max_records";

  /**
   * Time to live in milliseconds.  If zero, time-based expiration is
   * disabled.
   */
  @InterfaceStability.Evolving
  public static final String CONF_CACHE_ENTRY_TTL =
      "fs.metadatastore.local.ttl";

  /** Contains directories and files. */
  private Cache<Path, PathMetadata> fileCache;

  /** Contains directory listings. */
  private Cache<Path, DirListingMetadata> dirCache;

  private FileSystem fs;
  /* Null iff this FS does not have an associated URI host. */
  private String uriHost;

  @Override
  public void initialize(FileSystem fileSystem) throws IOException {
    Preconditions.checkNotNull(fileSystem);
    fs = fileSystem;
    URI fsURI = fs.getUri();
    uriHost = fsURI.getHost();
    if (uriHost != null && uriHost.equals("")) {
      uriHost = null;
    }

    initialize(fs.getConf());
  }

  @Override
  public void initialize(Configuration conf) throws IOException {
    Preconditions.checkNotNull(conf);
    int maxRecords = conf.getInt(CONF_MAX_RECORDS, DEFAULT_MAX_RECORDS);
    if (maxRecords < 4) {
      maxRecords = 4;
    }
    int ttl = conf.getInt(CONF_CACHE_ENTRY_TTL, DEFAULT_CACHE_ENTRY_TTL_MSEC);

    CacheBuilder builder = CacheBuilder.newBuilder().maximumSize(maxRecords);
    if (ttl >= 0) {
      builder.expireAfterAccess(ttl, TimeUnit.MILLISECONDS);
    }

    fileCache = builder.build();
    dirCache = builder.build();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(
        "LocalMetadataStore{");
    sb.append("uriHost='").append(uriHost).append('\'');
    sb.append('}');
    return sb.toString();
  }

  @Override
  public void delete(Path p) throws IOException {
    doDelete(p, false, true);
  }

  @Override
  public void forgetMetadata(Path p) throws IOException {
    doDelete(p, false, false);
  }

  @Override
  public void deleteSubtree(Path path) throws IOException {
    doDelete(path, true, true);
  }

  private synchronized void doDelete(Path p, boolean recursive, boolean
      tombstone) {

    Path path = standardize(p);

    // Delete entry from file cache, then from cached parent directory, if any

    deleteCacheEntries(path, tombstone);

    if (recursive) {
      // Remove all entries that have this dir as path prefix.
      deleteEntryByAncestor(path, dirCache, tombstone);
      deleteEntryByAncestor(path, fileCache, tombstone);
    }
  }

  @Override
  public synchronized PathMetadata get(Path p) throws IOException {
    return get(p, false);
  }

  @Override
  public PathMetadata get(Path p, boolean wantEmptyDirectoryFlag)
      throws IOException {
    Path path = standardize(p);
    synchronized (this) {
      PathMetadata m = fileCache.getIfPresent(path);

      if (wantEmptyDirectoryFlag && m != null &&
          m.getFileStatus().isDirectory()) {
        m.setIsEmptyDirectory(isEmptyDirectory(p));
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("get({}) -> {}", path, m == null ? "null" : m.prettyPrint());
      }
      return m;
    }
  }

  /**
   * Determine if directory is empty.
   * Call with lock held.
   * @param p a Path, already filtered through standardize()
   * @return TRUE / FALSE if known empty / not-empty, UNKNOWN otherwise.
   */
  private Tristate isEmptyDirectory(Path p) {
    DirListingMetadata dirMeta = dirCache.getIfPresent(p);
    return dirMeta.withoutTombstones().isEmpty();
  }

  @Override
  public synchronized DirListingMetadata listChildren(Path p) throws
      IOException {
    Path path = standardize(p);
    DirListingMetadata listing = dirCache.getIfPresent(path);
    if (LOG.isDebugEnabled()) {
      LOG.debug("listChildren({}) -> {}", path,
          listing == null ? "null" : listing.prettyPrint());
    }
    // Make a copy so callers can mutate without affecting our state
    return listing == null ? null : new DirListingMetadata(listing);
  }

  @Override
  public void move(Collection<Path> pathsToDelete,
      Collection<PathMetadata> pathsToCreate) throws IOException {

    Preconditions.checkNotNull(pathsToDelete, "pathsToDelete is null");
    Preconditions.checkNotNull(pathsToCreate, "pathsToCreate is null");
    Preconditions.checkArgument(pathsToDelete.size() == pathsToCreate.size(),
        "Must supply same number of paths to delete/create.");

    // I feel dirty for using reentrant lock. :-|
    synchronized (this) {

      // 1. Delete pathsToDelete
      for (Path meta : pathsToDelete) {
        LOG.debug("move: deleting metadata {}", meta);
        delete(meta);
      }

      // 2. Create new destination path metadata
      for (PathMetadata meta : pathsToCreate) {
        LOG.debug("move: adding metadata {}", meta);
        put(meta);
      }

      // 3. We now know full contents of all dirs in destination subtree
      for (PathMetadata meta : pathsToCreate) {
        FileStatus status = meta.getFileStatus();
        if (status == null || status.isDirectory()) {
          continue;
        }
        DirListingMetadata dir = listChildren(status.getPath());
        if (dir != null) {  // could be evicted already
          dir.setAuthoritative(true);
        }
      }
    }
  }

  @Override
  public void put(PathMetadata meta) throws IOException {

    Preconditions.checkNotNull(meta);
    FileStatus status = meta.getFileStatus();
    Path path = standardize(status.getPath());
    synchronized (this) {

      /* Add entry for this file. */
      if (LOG.isDebugEnabled()) {
        LOG.debug("put {} -> {}", path, meta.prettyPrint());
      }
      fileCache.put(path, meta);

      /* Directory case:
       * We also make sure we have an entry in the dirCache, so subsequent
       * listStatus(path) at least see the directory.
       *
       * If we had a boolean flag argument "isNew", we would know whether this
       * is an existing directory the client discovered via getFileStatus(),
       * or if it is a newly-created directory.  In the latter case, we would
       * be able to mark the directory as authoritative (fully-cached),
       * saving round trips to underlying store for subsequent listStatus()
       */

      if (status.isDirectory()) {
        DirListingMetadata dir = dirCache.getIfPresent(path);
        if (dir == null) {
          dirCache.put(path, new DirListingMetadata(path, DirListingMetadata
              .EMPTY_DIR, false));
        }
      }

      /* Update cached parent dir. */
      Path parentPath = path.getParent();
      if (parentPath != null) {
        DirListingMetadata parent = dirCache.getIfPresent(parentPath);
        if (parent == null) {
        /* Track this new file's listing in parent.  Parent is not
         * authoritative, since there may be other items in it we don't know
         * about. */
          parent = new DirListingMetadata(parentPath,
              DirListingMetadata.EMPTY_DIR, false);
          dirCache.put(parentPath, parent);
        }
        parent.put(status);
      }
    }
  }

  @Override
  public synchronized void put(DirListingMetadata meta) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("put dirMeta {}", meta.prettyPrint());
    }
    dirCache.put(standardize(meta.getPath()), meta);
    put(meta.getListing());
  }

  public synchronized void put(Collection<PathMetadata> metas) throws
      IOException {
    for (PathMetadata meta : metas) {
      put(meta);
    }
  }

  @Override
  public void close() throws IOException {

  }

  @Override
  public void destroy() throws IOException {
    if (dirCache != null) {
      dirCache.invalidateAll();
    }
  }

  @Override
  public void prune(long modTime) throws IOException{
    prune(modTime, "");
  }

  @Override
  public synchronized void prune(long modTime, String keyPrefix)
      throws IOException {
    Iterator<Map.Entry<Path, PathMetadata>> files =
        fileCache.asMap().entrySet().iterator();
    while (files.hasNext()) {
      Map.Entry<Path, PathMetadata> entry = files.next();
      if (expired(entry.getValue().getFileStatus(), modTime, keyPrefix)) {
        files.remove();
      }
    }
    Iterator<Map.Entry<Path, DirListingMetadata>> dirs =
        dirCache.asMap().entrySet().iterator();
    while (dirs.hasNext()) {
      Map.Entry<Path, DirListingMetadata> entry = dirs.next();
      Path path = entry.getKey();
      DirListingMetadata metadata = entry.getValue();
      Collection<PathMetadata> oldChildren = metadata.getListing();
      Collection<PathMetadata> newChildren = new LinkedList<>();

      for (PathMetadata child : oldChildren) {
        FileStatus status = child.getFileStatus();
        if (!expired(status, modTime, keyPrefix)) {
          newChildren.add(child);
        }
      }
      if (newChildren.size() != oldChildren.size()) {
        dirCache.put(path, new DirListingMetadata(path, newChildren, false));
        if (!path.isRoot()) {
          DirListingMetadata parent = null;
          parent = dirCache.getIfPresent(path.getParent());
          if (parent != null) {
            parent.setAuthoritative(false);
          }
        }
      }
    }
  }

  private boolean expired(FileStatus status, long expiry, String keyPrefix) {
    // remove the protocol from path string to be able to compare
    String bucket = status.getPath().toUri().getHost();
    String statusTranslatedPath = "";
    if(bucket != null && !bucket.isEmpty()){
      // if there's a bucket, (well defined host in Uri) the pathToParentKey
      // can be used to get the path from the status
      statusTranslatedPath =
          PathMetadataDynamoDBTranslation.pathToParentKey(status.getPath());
    } else {
      // if there's no bucket in the path the pathToParentKey will fail, so
      // this is the fallback to get the path from status
      statusTranslatedPath = status.getPath().toUri().getPath();
    }

    // Note: S3 doesn't track modification time on directories, so for
    // consistency with the DynamoDB implementation we ignore that here
    return status.getModificationTime() < expiry && !status.isDirectory()
      && statusTranslatedPath.startsWith(keyPrefix);
  }

  @VisibleForTesting
  static <T> void deleteEntryByAncestor(Path ancestor, Cache<Path, T> cache,
                                       boolean tombstone) {
    for (Iterator<Map.Entry<Path, T>> it = cache.asMap().entrySet().iterator();
         it.hasNext();) {
      Map.Entry<Path, T> entry = it.next();
      Path f = entry.getKey();
      T meta = entry.getValue();
      if (isAncestorOf(ancestor, f)) {
        if (tombstone) {
          if (meta instanceof PathMetadata) {
            cache.put(f, (T) PathMetadata.tombstone(f));
          } else if (meta instanceof DirListingMetadata) {
            it.remove();
          } else {
            throw new IllegalStateException("Unknown type in cache");
          }
        } else {
          it.remove();
        }
      }
    }
  }

  /**
   * @return true iff 'ancestor' is ancestor dir in path 'f'.
   * All paths here are absolute.  Dir does not count as its own ancestor.
   */
  private static boolean isAncestorOf(Path ancestor, Path f) {
    String aStr = ancestor.toString();
    if (!ancestor.isRoot()) {
      aStr += "/";
    }
    String fStr = f.toString();
    return (fStr.startsWith(aStr));
  }

  /**
   * Update fileCache and dirCache to reflect deletion of file 'f'.  Call with
   * lock held.
   */
  private void deleteCacheEntries(Path path, boolean tombstone) {

    // Remove target file/dir
    LOG.debug("delete file entry for {}", path);
    if (tombstone) {
      fileCache.put(path, PathMetadata.tombstone(path));
    } else {
      fileCache.invalidate(path);
    }

    // Update this and parent dir listing, if any

    /* If this path is a dir, remove its listing */
    LOG.debug("removing listing of {}", path);

    dirCache.invalidate(path);

    /* Remove this path from parent's dir listing */
    Path parent = path.getParent();
    if (parent != null) {
      DirListingMetadata dir = null;
      dir = dirCache.getIfPresent(parent);
      if (dir != null) {
        LOG.debug("removing parent's entry for {} ", path);
        if (tombstone) {
          dir.markDeleted(path);
        } else {
          dir.remove(path);
        }
      }
    }
  }

  /**
   * Return a "standardized" version of a path so we always have a consistent
   * hash value.  Also asserts the path is absolute, and contains host
   * component.
   * @param p input Path
   * @return standardized version of Path, suitable for hash key
   */
  private Path standardize(Path p) {
    Preconditions.checkArgument(p.isAbsolute(), "Path must be absolute");
    URI uri = p.toUri();
    if (uriHost != null) {
      Preconditions.checkArgument(StringUtils.isNotEmpty(uri.getHost()));
    }
    return p;
  }

  @Override
  public Map<String, String> getDiagnostics() throws IOException {
    Map<String, String> map = new HashMap<>();
    map.put("name", "local://metadata");
    map.put("uriHost", uriHost);
    map.put("description", "Local in-VM metadata store for testing");
    map.put(MetadataStoreCapabilities.PERSISTS_AUTHORITATIVE_BIT,
        Boolean.toString(true));
    return map;
  }

  @Override
  public void updateParameters(Map<String, String> parameters)
      throws IOException {
  }
}
