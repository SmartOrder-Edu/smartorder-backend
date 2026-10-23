package com.smartorder.edu.common.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 网盘文件入库消息体（秒传分支异步落库）。
 * <p>
 * 刻意不使用 {@code Map<String, Object>} 承载消息，原因有三：
 * <ol>
 *   <li>Map 的 key 在编译期没有任何约束，生产端改 key 消费端不会报错，只能在线上撞；</li>
 *   <li>{@code Map.of(...)} 不接受 null 值，传入 null 会直接抛 NPE；</li>
 *   <li>把整行 {@code FileResource} 实体塞进消息，会让 MQ 契约与数据库 schema 强耦合，
 *       表加字段消息就变大，表删字段消费端可能 NPE。</li>
 * </ol>
 * 因此这里只携带消费端真正需要的字段，其中 {@link #objectKey} 是唯一需要从被复用文件继承的信息。
 *
 * @see com.smartorder.edu.common.config.YunPanMQConfig
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileSaveMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件ID（雪花ID字符串，与前端保持字符串口径，消费端 Long.parseLong 还原） */
    private String fileId;

    /** 上传用户ID */
    private Long userId;

    /** 目标文件夹ID，null 归一化为 0（根目录） */
    private Long targetFolderId;

    /** 文件名称 */
    private String fileName;

    /** 文件大小（字节），取被复用记录的库中真实值，不信任前端入参 */
    private Long fileSize;

    /** 文件内容哈希 */
    private String fileHash;

    /** 被复用文件的 OSS objectKey，秒传记录与该对象共享同一份存储 */
    private String objectKey;
}
