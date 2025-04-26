package com.bumptech.glide.load;

/** 指示一些检索到的数据的起源 */
public enum DataSource {
  /**
   * 数据可能是从设备本地检索的，
   * 尽管它可能是通过从远程源获取数据的内容提供商获得的。
   */
  LOCAL,
  /** 从设备以外的远程源检索的 */
  REMOTE,
  /** 从设备缓存中检索到未经修改的数据 */
  DATA_DISK_CACHE,
  /** 从设备缓存中修改过的内容中检索到的 */
  RESOURCE_DISK_CACHE,
  /** 从内存缓存中检索的 */
  MEMORY_CACHE,
}
