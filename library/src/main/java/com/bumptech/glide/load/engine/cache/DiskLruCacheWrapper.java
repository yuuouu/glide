/*
 * Copyright (c) 2013. Bump Technologies Inc. All Rights Reserved.
 */

package com.bumptech.glide.load.engine.cache;

import android.util.Log;
import com.bumptech.glide.disklrucache.DiskLruCache;
import com.bumptech.glide.disklrucache.DiskLruCache.Value;
import com.bumptech.glide.load.Key;
import java.io.File;
import java.io.IOException;

/**
 * The default DiskCache implementation. There must be no more than one active instance for a given
 * directory at a time.
 *
 * @see #get(java.io.File, long)
 */
public class DiskLruCacheWrapper implements DiskCache {
  private static final String TAG = "DiskLruCacheWrapper";

  private static final int APP_VERSION = 1;
  private static final int VALUE_COUNT = 1;
  private static DiskLruCacheWrapper wrapper;

  private final SafeKeyGenerator safeKeyGenerator;
  private final File directory;
  private final long maxSize;
  private final DiskCacheWriteLocker writeLocker = new DiskCacheWriteLocker();
  private DiskLruCache diskLruCache;

  /**
   * Get a DiskCache in the given directory and size. If a disk cache has already been created with
   * a different directory and/or size, it will be returned instead and the new arguments will be
   * ignored.
   *
   * @param directory The directory for the disk cache
   * @param maxSize The max size for the disk cache
   * @return The new disk cache with the given arguments, or the current cache if one already exists
   * @deprecated Use {@link #create(File, long)} to create a new cache with the specified arguments.
   */
  @SuppressWarnings("deprecation")
  @Deprecated
  public static synchronized DiskCache get(File directory, long maxSize) {
    // TODO calling twice with different arguments makes it return the cache for the same
    // directory, it's public!
    if (wrapper == null) {
      wrapper = new DiskLruCacheWrapper(directory, maxSize);
    }
    return wrapper;
  }

  /**
   * Create a new DiskCache in the given directory with a specified max size.
   *
   * @param directory The directory for the disk cache
   * @param maxSize The max size for the disk cache
   * @return The new disk cache with the given arguments
   */
  @SuppressWarnings("deprecation")
  public static DiskCache create(File directory, long maxSize) {
    return new DiskLruCacheWrapper(directory, maxSize);
  }

  /**
   * @deprecated Do not extend this class.
   */
  @Deprecated
  // Deprecated public API.
  @SuppressWarnings({"WeakerAccess", "DeprecatedIsStillUsed"})
  protected DiskLruCacheWrapper(File directory, long maxSize) {
    this.directory = directory;
    this.maxSize = maxSize;
    this.safeKeyGenerator = new SafeKeyGenerator();
  }

  private synchronized DiskLruCache getDiskCache() throws IOException {
    if (diskLruCache == null) {
      diskLruCache = DiskLruCache.open(directory, APP_VERSION, VALUE_COUNT, maxSize);
    }
    return diskLruCache;
  }

  @Override
  public File get(Key key) {
    String safeKey = safeKeyGenerator.getSafeKey(key);
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      Log.v(TAG, "Get: Obtained: " + safeKey + " for for Key: " + key);
    }
    File result = null;
    try {
      // It is possible that the there will be a put in between these two gets. If so that shouldn't
      // be a problem because we will always put the same value at the same key so our input streams
      // will still represent the same data.
      final DiskLruCache.Value value = getDiskCache().get(safeKey);
      if (value != null) {
        result = value.getFile(0);
      }
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to get from disk cache", e);
      }
    }
    return result;
  }

  /**
   * 通过加锁、检查缓存是否已存在、协调写入流程、回滚失败，确保 Glide 的磁盘缓存在多线程环境下始终是可靠、一致、且高性能的。
   */
  @Override
  public void put(Key key, Writer writer) {
    // 我们希望确保在多线程中 put 操作阻塞，以便在 put 操作完成时数据可用。如果我们在获取锁时发现数据已写入，那么我们实际上可能就不会写入任何数据。
    String safeKey = safeKeyGenerator.getSafeKey(key);
    writeLocker.acquire(safeKey);
    try {
      if (Log.isLoggable(TAG, Log.VERBOSE)) {
        Log.v(TAG, "Put: Obtained: " + safeKey + " for for Key: " + key);
      }
      try {
        // 我们假设我们只需要放入一次，因此如果在我们尝试获取锁时写入了数据，我们就可以简单地中止。
        DiskLruCache diskCache = getDiskCache();
        Value current = diskCache.get(safeKey);
        //  二次检查，避免重复写入。即便两个线程几乎同时 put，只要一个先完成，另一个会自动跳过。
        if (current != null) {
          return;
        }

        DiskLruCache.Editor editor = diskCache.edit(safeKey);
        if (editor == null) {
          // 如果 edit 返回 null，说明别的线程已经在写这个 key 了。
          throw new IllegalStateException("Had two simultaneous puts for: " + safeKey);
        }
        try {
          File file = editor.getFile(0);
          if (writer.write(file)) {
            editor.commit();
          }
        } finally {
          editor.abortUnlessCommitted();
        }
      } catch (IOException e) {
        if (Log.isLoggable(TAG, Log.WARN)) {
          Log.w(TAG, "Unable to put to disk cache", e);
        }
      }
    } finally {
      // 锁释放
      writeLocker.release(safeKey);
    }
  }

  @Override
  public void delete(Key key) {
    String safeKey = safeKeyGenerator.getSafeKey(key);
    try {
      getDiskCache().remove(safeKey);
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to delete from disk cache", e);
      }
    }
  }

  @Override
  public synchronized void clear() {
    try {
      getDiskCache().delete();
    } catch (IOException e) {
      if (Log.isLoggable(TAG, Log.WARN)) {
        Log.w(TAG, "Unable to clear disk cache or disk cache cleared externally", e);
      }
    } finally {
      // Delete can close the cache but still throw. If we don't null out the disk cache here, every
      // subsequent request will try to act on a closed disk cache and fail. By nulling out the disk
      // cache we at least allow for attempts to open the cache in the future. See #2465.
      resetDiskCache();
    }
  }

  private synchronized void resetDiskCache() {
    diskLruCache = null;
  }
}
