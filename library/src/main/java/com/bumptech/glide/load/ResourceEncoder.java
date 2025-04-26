package com.bumptech.glide.load;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.engine.Resource;

/**
 * 用于将数据从资源写入某些持久数据存储（例如本地文件缓存）的接口
 *
 * @param <T> 资源所包含的数据类型。
 */
public interface ResourceEncoder<T> extends Encoder<Resource<T>> {
  // specializing the generic arguments
  @NonNull
  EncodeStrategy getEncodeStrategy(@NonNull Options options);
}
