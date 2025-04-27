package com.bumptech.glide;

/** An enum for dynamically modifying the amount of memory Glide is able to use. */
public enum MemoryCategory {
  /**
   * 内存缓存和位图池最多使用其初始最大大小的一半
   */
  LOW(0.5f),
  /** 内存缓存和位图池最多使用其初始最大大小 */
  NORMAL(1f),
  /**
   * 内存缓存和位图池最多使用其初始最大大小的 1.5 倍
   */
  HIGH(1.5f);

  private final float multiplier;

  MemoryCategory(float multiplier) {
    this.multiplier = multiplier;
  }

  /**
   * 返回应应用于 Glide 内存缓存和位图池的初始最大大小的乘数
   */
  public float getMultiplier() {
    return multiplier;
  }
}
