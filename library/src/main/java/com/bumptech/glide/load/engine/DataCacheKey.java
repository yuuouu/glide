package com.bumptech.glide.load.engine;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.Key;
import java.security.MessageDigest;

/** 原始源数据 + 任何请求的签名的缓存键 */
final class DataCacheKey implements Key {

  private final Key sourceKey;
  private final Key signature;

  /**
   * @param sourceKey 原始数据标识（如URL、文件路径等经过SHA-256哈希后的值）
   * @param signature 应用签名（包含磁盘缓存版本、Transformations等配置信息）
   */
  DataCacheKey(Key sourceKey, Key signature) {
    this.sourceKey = sourceKey;
    this.signature = signature;
  }

  Key getSourceKey() {
    return sourceKey;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof DataCacheKey) {
      DataCacheKey other = (DataCacheKey) o;
      return sourceKey.equals(other.sourceKey) && signature.equals(other.signature);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = sourceKey.hashCode();
    result = 31 * result + signature.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "DataCacheKey{" + "sourceKey=" + sourceKey + ", signature=" + signature + '}';
  }

  @Override
  public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
    sourceKey.updateDiskCacheKey(messageDigest);
    signature.updateDiskCacheKey(messageDigest);
  }
}
