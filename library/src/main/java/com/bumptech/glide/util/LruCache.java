package com.bumptech.glide.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.engine.Resource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A general purpose size limited cache that evicts items using an LRU algorithm. By default every
 * item is assumed to have a size of one. Subclasses can override {@link #getSize(Object)}} to
 * change the size on a per item basis.
 *
 * @param <T> The type of the keys.
 * @param <Y> The type of the values.
 */
public class LruCache<T, Y> {
  // accessOrder 为 true 时是按照访问顺序（get/put）排列
  private final Map<T, Entry<Y>> cache = new LinkedHashMap<>(100, 0.75f, /*accessOrder=*/ true);
  private final long initialMaxSize;
  private long maxSize;
  private long currentSize;

  /**
   * Constructor for LruCache.
   *
   * @param size The maximum size of the cache, the units must match the units used in {@link
   *     #getSize(Object)}.
   */
  public LruCache(long size) {
    this.initialMaxSize = size;
    this.maxSize = size;
  }

  /**
   * 设置一个大小乘数，该乘数将应用于构造函数中提供的大小，以设置缓存的新大小。
   * 如果新大小小于当前大小，则条目将被逐出，直到当前大小小于或等于新大小。
   * <br>
   * 只在测试数据中使用
   * @param multiplier The multiplier to apply.
   */
  public synchronized void setSizeMultiplier(float multiplier) {
    if (multiplier < 0) {
      throw new IllegalArgumentException("Multiplier must be >= 0");
    }
    maxSize = Math.round(initialMaxSize * multiplier);
    evict();
  }

  /**
   * 返回指定项目的大小，默认为 1。单位必须与构造函数中传入的 size 单位一致。
   * 子类可以重写此方法，以各种单位（通常为字节）返回大小。
   *
   * @param item The item to get the size of.
   */
  protected int getSize(@Nullable Y item) {
    return 1;
  }

  /** Returns the number of entries stored in cache. */
  protected synchronized int getCount() {
    return cache.size();
  }

  /**
   * 每当缓存中的某个项目被移除时，都会调用此回调函数。子类可以重写此回调函数。
   *
   * @param key The key of the evicted item.
   * @param item The evicted item.
   * @see com.bumptech.glide.load.engine.ResourceRecycler#recycle(Resource, boolean)
   */
  protected void onItemEvicted(@NonNull T key, @Nullable Y item) {
    // optional override
  }

  /** 返回缓存的当前最大值（以字节为单位） */
  public synchronized long getMaxSize() {
    return maxSize;
  }

  /** 返回缓存中所有项目的大小总和。 */
  public synchronized long getCurrentSize() {
    return currentSize;
  }

  /**
   * Returns true if there is a value for the given key in the cache.
   *
   * @param key The key to check.
   */
  public synchronized boolean contains(@NonNull T key) {
    return cache.containsKey(key);
  }

  /**
   * 如果不存在此类项目，则将其返回给定键或空的项目中的项目
   * <br>
   * LinkedHashMap.get() 中有一个 afterNodeAccess() 方法，用于将节点放到最前面
   *
   * @param key The key to check.
   * @see LinkedHashMap#get(Object)
   */
  @Nullable
  public synchronized Y get(@NonNull T key) {
    // 这里调用 LinkedHashMap.get 方法，然后会调用 afterNodeAccess() 方法，将节点放到最前面
    Entry<Y> entry = cache.get(key);
    return entry != null ? entry.value : null;
  }

  /**
   * 使用给定的键将给定项添加到缓存中，并返回给定键可能已存在于缓存中的任何先前条目。
   * <p>如果项的大小大于缓存的总大小，则不会将其添加到缓存中，而是使用给定的键和项同步调用 {@link #onItemEvicted(Object, Object)}
   *
   * <p>项的大小通过 {@link #getSize(Object)} 获取实际字节大小。
   * 为了避免该方法在不同时间调用同一对象时返回不同值的错误，大小值将在 put 中获取并保留，直到项被逐出、替换或移除。
   *
   * <p>如果项为 null，此处的行为会有些奇怪。在大多数情况下，它类似于使用给定的键简单地调用 {@link #remove(Object)}。区别在于，
   * 使用 null 项调用此方法将导致缓存中保留一个值为 null 且大小为 0 的条目。唯一的实际后果是，在某些时候可能会使用给定的键和 null 值调用 {@link #onItemEvicted(Object, Object)}
   * 理想情况下，我们应该使用 null 项调用此方法，使其与 {@link #remove(Object)}完全相同，但我们保留了这种奇怪的行为以兼容旧版本 :(
   *
   * @param key The key to add the item at.
   * @param item The item to add.
   */
  @Nullable
  public synchronized Y put(@NonNull T key, @Nullable Y item) {
    final int itemSize = getSize(item);
    if (itemSize >= maxSize) {
      /**
       * 如果当前项的字节数大于可用缓存最大值，则不进行缓存
       * @see com.bumptech.glide.load.engine.cache.LruResourceCache#getSize(Resource)
       */
      onItemEvicted(key, item);
      return null;
    }
    if (item != null) {
      currentSize += itemSize;
    }
    /**
     * 调用 LinkedHashMap.put，因为没有重写，所有调用的实际是 HashMap.put
     *
     * @see java.util.HashMap#put(Object, Object)
     */
    @Nullable Entry<Y> old = cache.put(key, item == null ? null : new Entry<>(item, itemSize));
    if (old != null) {
      // 在内存缓存中存在该资源，则减去旧item占用的字节数
      currentSize -= old.size;
      if (!old.value.equals(item)) {
        /**
         * 如果旧item和当前item的资源不是同一个，则清除旧资源
         * 在使用 EngineKey 时几乎不可能出现这种情况吧。但如果 key 没有实现 hashCode/equals 的话就会大概率出现
         * https://chatgpt.com/share/680e44ca-a410-800c-9fc2-5d44383f5e6f
         */
        onItemEvicted(key, old.value);
      }
    }
    // 去判断内存是不是满了，满了的话要删除最近未使用的元素
    evict();
    return old != null ? old.value : null;
  }

  /**
   * Removes the item at the given key and returns the removed item if present, and null otherwise.
   *
   * @param key The key to remove the item at.
   */
  @Nullable
  public synchronized Y remove(@NonNull T key) {
    Entry<Y> entry = cache.remove(key);
    if (entry == null) {
      return null;
    }
    currentSize -= entry.size;
    return entry.value;
  }

  /** 清除缓存中的所有项目 */
  public void clearMemory() {
    trimToSize(0);
  }

  /**
   * 从缓存中删除最近最少使用的项目，直到当前大小小于给定大小
   *
   * @param size The size the cache should be less than.
   */
  protected synchronized void trimToSize(long size) {
    Map.Entry<T, Entry<Y>> last;
    Iterator<Map.Entry<T, Entry<Y>>> cacheIterator;
    while (currentSize > size) {
      // LinkedHashMap默认的访问顺序模式，最近最少使用的元素排在最前面
      cacheIterator = cache.entrySet().iterator();
      // 拿到最久未访问的元素
      last = cacheIterator.next();
      final Entry<Y> toRemove = last.getValue();
      currentSize -= toRemove.size;
      // 移除该引用
      final T key = last.getKey();
      cacheIterator.remove();
      // 返回接口，去回收实际内存
      onItemEvicted(key, toRemove.value);
    }
  }

  private void evict() {
    trimToSize(maxSize);
  }

  @Synthetic
  static final class Entry<Y> {
    final Y value;
    final int size;

    @Synthetic
    Entry(Y value, int size) {
      this.value = value;
      this.size = size;
    }
  }
}
