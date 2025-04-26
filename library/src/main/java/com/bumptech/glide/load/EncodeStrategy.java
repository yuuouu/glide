package com.bumptech.glide.load;

/**
 * 详细说明 {@link com.bumptech.glide.load.ResourceEncoder} 如何对资源进行编码以进行缓存。
 */
public enum EncodeStrategy {
  /**
   * 编写原始的未修改数据，以供磁盘磁盘，而不包括倒数采样或转换
   */
  SOURCE,

  /** 将解码，倒数采样和转换为磁盘的数据写入 */
  TRANSFORMED,

  /** 不会写入数据。 */
  NONE,
}
