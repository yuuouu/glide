package com.bumptech.glide.load.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;

/**
 * 数据访问者/获取器，用于延迟检索可用于加载资源的数据。
 *
 * <p>{@link com.bumptech.glide.load.model.ModelLoader} 每次加载资源时都会创建一个新实例。对于任何给定的加载，
 * 可能会或可能不会调用 {@link #loadData(com.bumptech.glide.Priority, com.bumptech.glide.load.data.DataFetcher.DataCallback)} 函数，
 * 具体取决于相应资源是否已缓存。Cancel 函数也可能会被调用。
 * 如果调用了 {@link #loadData(com.bumptech.glide.Priority, com.bumptech.glide.load.data.DataFetcher.DataCallback)}} 函数，
 * 那么 {@link #cleanup()} 函数也会被调用。
 *
 * @param <T> 待加载数据的类型（InputStream、byte[]、File 等）。
 */
public interface DataFetcher<T> {

  /**
   * 当数据已加载且可用时，或加载失败时必须调用的回调。
   *
   * @param <T> 待加载数据的类型
   */
  interface DataCallback<T> {

    /**
     * 如果加载成功，则使用加载的数据进行调用，如果加载失败，则使用 {@code null} 进行调用。
     */
    void onDataReady(@Nullable T data);

    /**
     * 加载失败时调用。
     *
     * @param e 一个非空的 {@link Exception}，指示加载失败的原因。
     */
    void onLoadFailed(@NonNull Exception e);
  }

  /**
   * 获取可解码资源的数据。
   *
   * <p>此方法始终在后台线程中调用，因此可以安全地执行长时间运行的任务。
   * 任何调用的第三方库都必须是线程安全的（或将工作移至其他线程）。
   * 因为此方法将从 {@link java.util.concurrent.ExecutorService} 中的线程调用，而该线程可能包含多个后台线程。您
   * <b>必须</b> 在请求完成后使用 {@link DataCallback}。
   *
   * <p>您可以将获取工作移至其他线程，并从那里调用回调。
   *
   * <p>仅当相应资源不在缓存中时，才会调用此方法。
   *
   * <p>注意 - 此方法将在后台线程中运行，因此阻塞 I/O 是安全的。
   *
   * @param priority 请求完成的优先级
   * @param callback 请求完成时使用的回调函数
   * @see #cleanup() 返回的数据将被清理
   */
  void loadData(@NonNull Priority priority, @NonNull DataCallback<? super T> callback);

  /**
   * 清理或回收此数据获取器使用的任何资源。此方法将在
   * finally 块中调用，在 {@link #loadData(com.bumptech.glide.Priority, com.bumptech.glide.load.data.DataFetcher.DataCallback)} 提供的数据被
   * {@link com.bumptech.glide.load.ResourceDecoder} 解码之后。
   *
   * <p>注意 - 此方法将在后台线程运行，因此阻塞 I/O 是安全的.
   */
  void cleanup();

  /**
   * 当加载不再相关且已被取消时，将调用此方法。此方法无需保证任何正在进行的加载不会完成。它也可以在加载开始之前或完成之后调用。
   *
   * <p>使用此方法的最佳方式是取消所有尚未开始的加载，但允许正在进行的加载完成，因为我们通常希望在不久的将来在不同的视图中显示相同的资源。
   *
   * <p>注意 - 此方法将在主线程上运行，因此它不应执行阻塞操作，并且应该快速完成。
   */
  void cancel();

  /**
   * 返回此访问者将尝试获取的数据类
   */
  @NonNull
  Class<T> getDataClass();

  /**
   * 返回此获取器将从中返回数据的数据源 {@link com.bumptech.glide.load.DataSource}
   */
  @NonNull
  DataSource getDataSource();
}
