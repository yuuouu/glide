package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import androidx.core.util.Pools;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Synthetic;
import com.bumptech.glide.util.pool.FactoryPools;
import com.bumptech.glide.util.pool.StateVerifier;

/**
 * 将所有对 {@link Resource#recycle()} 的调用推迟到 {@link #unlock()} 调用之后的资源。
 *
 * <p>如果资源在 {@link #unlock()} 之前被回收，则 {@link #unlock()} 也会回收该资源。
 *
 */
final class LockedResource<Z> implements Resource<Z>, FactoryPools.Poolable {
  private static final Pools.Pool<LockedResource<?>> POOL = FactoryPools.threadSafe(20, LockedResource::new);
  private final StateVerifier stateVerifier = StateVerifier.newInstance();
  private Resource<Z> toWrap;
  private boolean isLocked;
  private boolean isRecycled;

  @SuppressWarnings("unchecked")
  @NonNull
  static <Z> LockedResource<Z> obtain(Resource<Z> resource) {
    LockedResource<Z> result = Preconditions.checkNotNull((LockedResource<Z>) POOL.acquire());
    result.init(resource);
    return result;
  }

  @SuppressWarnings("WeakerAccess")
  @Synthetic
  LockedResource() {}

  private void init(Resource<Z> toWrap) {
    isRecycled = false;
    isLocked = true;
    this.toWrap = toWrap;
  }

  private void release() {
    toWrap = null;
    POOL.release(this);
  }

  synchronized void unlock() {
    stateVerifier.throwIfRecycled();

    if (!isLocked) {
      throw new IllegalStateException("Already unlocked");
    }
    this.isLocked = false;
    if (isRecycled) {
      recycle();
    }
  }

  @NonNull
  @Override
  public Class<Z> getResourceClass() {
    return toWrap.getResourceClass();
  }

  @NonNull
  @Override
  public Z get() {
    return toWrap.get();
  }

  @Override
  public int getSize() {
    return toWrap.getSize();
  }

  @Override
  public synchronized void recycle() {
    stateVerifier.throwIfRecycled();

    this.isRecycled = true;
    if (!isLocked) {
      toWrap.recycle();
      release();
    }
  }

  @NonNull
  @Override
  public StateVerifier getVerifier() {
    return stateVerifier;
  }
}
