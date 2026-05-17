package com.smartorder.edu.common.common;

public class Constants {

    // Redis Key 前缀常量
    public static final String REDIS_UPLOAD_ID_PREFIX = "upload:id:";
    public static final String REDIS_OBJECT_KEY_PREFIX = "upload:key:";
    public static final String REDIS_ETAG_PREFIX = "upload:etag:";

    public static final String PENDING = "pending";
    public static final String PROCESSING = "processing";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String ACTIVE = "active";
    public static final String DELETED = "deleted";

    public static final String EMBEDDING = "embedding";
    public static final String EMBEDDED = "embedded";

}
