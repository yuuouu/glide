package com.bumptech.glide;

/**
 * Priorities for completing loads. If more than one load is queued at a time, the load with the
 * higher priority will be started first. Priorities are considered best effort, there are no
 * guarantees about the order in which loads will start or finish.
 */
public enum Priority {
  IMMEDIATE, // 最高优先级（ordinal=0）
  HIGH,      // 次高优先级（ordinal=1）
  NORMAL,    // 普通优先级（ordinal=2）
  LOW        // 最低优先级（ordinal=3）
}
