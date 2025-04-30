package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import androidx.core.util.Pools.Pool;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataRewinder;
import com.bumptech.glide.util.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 对于给定数据类的给定 {@link com.bumptech.glide.load.data.DataFetcher}，
 * 尝试获取数据，然后通过一个或多个 {@link com.bumptech.glide.load.engine.DecodePath} 运行。
 *
 * @param <Data>         待获取数据类型。
 * @param <ResourceType> 将在某个 {@link com.bumptech.glide.load.engine.DecodePath} 中解码的中间资源类型。
 * @param <Transcode>    加载和解码路径之一成功后，将作为结果返回的资源类型。
 */
public class LoadPath<Data, ResourceType, Transcode> {
  private final Class<Data> dataClass;
  private final Pool<List<Throwable>> listPool;
  private final List<? extends DecodePath<Data, ResourceType, Transcode>> decodePaths;
  private final String failureMessage;

  public LoadPath(
      Class<Data> dataClass,
      Class<ResourceType> resourceClass,
      Class<Transcode> transcodeClass,
      List<DecodePath<Data, ResourceType, Transcode>> decodePaths,
      Pool<List<Throwable>> listPool) {
    this.dataClass = dataClass;
    this.listPool = listPool;
    this.decodePaths = Preconditions.checkNotEmpty(decodePaths);
    failureMessage =
        "Failed LoadPath{"
            + dataClass.getSimpleName()
            + "->"
            + resourceClass.getSimpleName()
            + "->"
            + transcodeClass.getSimpleName()
            + "}";
  }

  public Resource<Transcode> load(DataRewinder<Data> rewinder, @NonNull Options options, int width, int height,
      DecodePath.DecodeCallback<ResourceType> decodeCallback) throws GlideException {
    List<Throwable> throwables = Preconditions.checkNotNull(listPool.acquire());
    try {
      return loadWithExceptionList(rewinder, options, width, height, decodeCallback, throwables);
    } finally {
      listPool.release(throwables);
    }
  }

  private Resource<Transcode> loadWithExceptionList(DataRewinder<Data> rewinder, @NonNull Options options, int width, int height,
      DecodePath.DecodeCallback<ResourceType> decodeCallback, List<Throwable> exceptions) throws GlideException {
    Resource<Transcode> result = null;
    //noinspection ForLoopReplaceableByForEach to improve perf
    for (int i = 0, size = decodePaths.size(); i < size; i++) {
      DecodePath<Data, ResourceType, Transcode> path = decodePaths.get(i);
      try {
        result = path.decode(rewinder, width, height, options, decodeCallback);
      } catch (GlideException e) {
        exceptions.add(e);
      }
      if (result != null) {
        break;
      }
    }

    if (result == null) {
      throw new GlideException(failureMessage, new ArrayList<>(exceptions));
    }

    return result;
  }

  public Class<Data> getDataClass() {
    return dataClass;
  }

  @Override
  public String toString() {
    return "LoadPath{" + "decodePaths=" + Arrays.toString(decodePaths.toArray()) + '}';
  }
}
