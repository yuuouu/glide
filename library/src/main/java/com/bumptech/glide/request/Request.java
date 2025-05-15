package com.bumptech.glide.request;

/** A request that loads a resource for an {@link com.bumptech.glide.request.target.Target}. */
public interface Request {
  /** 开始异步加载 */
  void begin();

  /**
   * 阻止从先前的请求加载任何位图，释放此请求占用的所有资源，显示当前占位符（如果提供了），并将此请求标记为已取消。
   */
  void clear();

  /**
   * 类似于 {@link #clear} ，用于处理正在进行的请求（或请求的某些部分），但如果请求已完成，则不执行任何操作。
   *
   * <p>与 {@link #clear()} 不同，此方法允许实现对请求的子部分执行不同的操作。
   * 例如，如果一个请求同时包含缩略图和主请求，并且请求的缩略图部分已完成，则此方法仅允许暂停请求的主部分，而不会清除先前已完成的缩略图部分。
   */
  void pause();

  /** 如果此请求正在运行且尚未完成或失败，则返回true。 */
  boolean isRunning();

  /** 如果请求成功完成，则返回为true。 */
  boolean isComplete();

  /** 如果请求已清除，则返回为true。 */
  boolean isCleared();

  /**
   * 如果设置了资源，则返回 true，即使请求尚未完成或主请求已失败。
   */
  boolean isAnyResourceSet();

  /**
   * 如果此 {@link Request} 与给定的 {@link Request} 等价（具有
   * 所有相同的选项和大小），则返回 {@code true}。
   *
   * <p>此方法与 {@link Object#equals(Object)} 相同，只是它特定于
   * {@link Request} 子类。我们不直接使用 {@link Object#equals(Object)}，因为我们
   * 在像 {@link java.util.Set} 这样的集合中跟踪 {@link Request}，并且对于两个不同的 {@link
   * com.bumptech.glide.request.target.Target}（例如），
   * 拥有两个不同的 {@link Request} 对象是完全合法的。使用类似但不同的方法，
   * 让我们在特定场景下有选择地比较 {@link Request} 对象。
   */
  boolean isEquivalentTo(Request other);
}
