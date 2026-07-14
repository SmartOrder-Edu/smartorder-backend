package com.smartorder.edu.student.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.service.IUserService;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.dto.FolderExportTask;
import com.smartorder.edu.student.domain.entity.FileFolder;
import com.smartorder.edu.student.domain.entity.FileResource;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;
import com.smartorder.edu.student.domain.vo.FileFolderVO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.mapper.FileFolderMapper;
import com.smartorder.edu.student.mapper.FileResourceMapper;
import com.smartorder.edu.student.service.IFileFolderService;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileFolderServiceImpl implements IFileFolderService {

    private final FileFolderMapper fileFolderMapper;
    private final FileResourceMapper fileResourceMapper;
    private final IUserService userService;
    private final AliOSSUtils aliOSSUtils;
    private final IUserFileQuotaService userFileQuotaService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    /** Redis key 前缀 */
    private static final String EXPORT_TASK_KEY = "yun:export:task:";
    private static final String EXPORT_LOCK_KEY = "yun:export:lock:";

    /** 压缩缓冲区大小：5MB */
    private static final int BUFFER_SIZE = 5 * 1024 * 1024;
    /** Redis TTL：25h（比 OSS 24h 生命周期多 1h） */
    private static final long TASK_TTL_HOURS = 25;

    @Override
    public FileFolderContentVO getFolderTree(Long userId) {
        // 1. 查出用户所有文件夹
        LambdaQueryWrapper<FileFolder> query = new LambdaQueryWrapper<>();
        query.eq(FileFolder::getUserId, userId);
        List<FileFolder> allFolders = fileFolderMapper.selectList(query);

        // 2. 按 parentId 分组
        Map<Long, List<FileFolder>> parentMap = allFolders.stream()
                .collect(Collectors.groupingBy(FileFolder::getParentId));

        // 3. 构建根节点
        FileFolderContentVO root = new FileFolderContentVO();
        root.setFolderId(0L);
        root.setFolderName("根目录");
        root.setFolders(buildChildFolders(parentMap.getOrDefault(0L, Collections.emptyList()), parentMap));
        root.setFiles(Collections.emptyList());

        return root;
    }

    private List<FileFolderVO> buildChildFolders(List<FileFolder> folders, Map<Long, List<FileFolder>> parentMap) {
        return folders.stream()
                .sorted(Comparator.comparing(FileFolder::getSort, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(FileFolder::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(f -> {
                    FileFolderVO vo = new FileFolderVO();
                    vo.setFolderId(f.getId());
                    vo.setFolderName(f.getFolderName());
                    vo.setFolderParentId(f.getParentId());
                    vo.setSort(f.getSort());
                    vo.setUpdateTime(f.getUpdateTime());
                    vo.setChildren(buildChildFolders(
                            parentMap.getOrDefault(f.getId(), Collections.emptyList()), parentMap));
                    return vo;
                }).toList();
    }

    @Override
    public FileFolderContentVO getFolderById(Long folderId, Long userId) {
        // 1-获取文件夹信息-folderId+folderName
        FileFolderContentVO fileFolderContentVO = new FileFolderContentVO();
        fileFolderContentVO.setFolderId(folderId);
        if (folderId == 0) {
            fileFolderContentVO.setFolderName("根目录");
        } else {
            FileFolder folder = fileFolderMapper.selectById(folderId);
            if (folder == null) {
                throw new BusinessException("文件夹不存在");
            }
            if (!folder.getUserId().equals(userId)) {
                throw new BusinessException("无权访问该文件夹");
            }
            fileFolderContentVO.setFolderName(folder.getFolderName());
        }

        // 2-获取子文件夹列表-folderId列表
        LambdaQueryWrapper<FileFolder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileFolder::getParentId, folderId);
        wrapper.eq(FileFolder::getUserId, userId);
        List<FileFolder> childFolders = fileFolderMapper.selectList(wrapper);
        List<FileFolderVO> fileFolderVOList = childFolders.stream().map(folder -> {
            FileFolderVO vo = new FileFolderVO();
            vo.setFolderId(folder.getId());
            vo.setFolderName(folder.getFolderName());
            vo.setFolderParentId(folder.getParentId());
            vo.setSort(folder.getSort());
            vo.setUpdateTime(folder.getUpdateTime());
            return vo;
        }).toList();
        fileFolderContentVO.setFolders(fileFolderVOList);

        // 3-获取子文件夹文件列表-file+fileName信息列表
        LambdaQueryWrapper<FileResource> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(FileResource::getFolderId, folderId);
        lambdaQueryWrapper.eq(FileResource::getUserId, userId);
        List<FileResource> fileResources = fileResourceMapper.selectList(lambdaQueryWrapper);
        List<FileResourceVO> fileResourceVOS = fileResources.stream().map(fileResource -> {
            FileResourceVO vo = new FileResourceVO();
            // 使用hutool包
            BeanUtils.copyProperties(fileResource, vo);
            return vo;
        }).toList();
        fileFolderContentVO.setFiles(fileResourceVOS);

        return fileFolderContentVO;
    }

    @Override
    public void createFolder(FileFolderDTO dto, @NotNull(message = "用户ID不能为空") Long userId) {
        // 验证用户是否为学生
        if (!userService.isStudentId(userId)) {
            throw new IllegalArgumentException("只有学生用户才能创建文件夹");
        }

        // 验证父文件夹是否存在且属于当前用户
        if(dto.getParentId()!=0){
        if (!checkFolderBelongsToUser(dto.getParentId(), userId)) {
            throw new IllegalArgumentException("父文件夹不存在或不属于当前用户");
        }
}
        // 验证同级文件夹名称是否重复
        if (checkSameFolderNameExists(dto.getParentId(), dto.getFolderName(), userId)) {
            throw new IllegalArgumentException("同级文件夹名称已存在");
        }

        FileFolder folder = new FileFolder();
        long snowflakeNextId = IdUtil.getSnowflakeNextId();
        folder.setId(snowflakeNextId);
        folder.setParentId(dto.getParentId());
        folder.setFolderName(dto.getFolderName());
        folder.setUserId(userId);
        fileFolderMapper.insert(folder);
    }

    @Override
    public void updateFolder(Long id, String folderName, Long userId) {
        FileFolder folder = fileFolderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new BusinessException("无权修改该文件夹");
        }
        folder.setFolderName(folderName);
        folder.setUpdateTime(LocalDateTime.now());
        fileFolderMapper.updateById(folder);
    }

    @Override
    @Transactional
    public void deleteFolder(Long folderId, Long userId) {
        // 1-验证文件夹是否存在且属于当前用户
        FileFolder folder = fileFolderMapper.selectById(folderId);
        if (folder == null || !folder.getUserId().equals(userId)) {
            throw new BusinessException("文件夹不存在或无权限");
        }
        // 2-查询文件夹下面所有的文件夹
        List<Long> folderList = findAllChildFolderIds(folderId, userId);
        folderList.add(folderId);   // 把自己添加进去

        // 3-查询文件夹下面所有的文件资源
        List<FileResource> fileList = findAllChildFileIds(folderList, userId);

        // 4-先删除OSS上的文件资源（仅当没有其他记录引用同一OSS文件时才删除，秒传场景下多个记录共享同一文件）
        fileList.forEach(file -> {
            try {
                if (file.getFileUrl() != null && !file.getFileUrl().isEmpty()) {
                    LambdaQueryWrapper<FileResource> refWrapper = new LambdaQueryWrapper<>();
                    refWrapper.eq(FileResource::getFileUrl, file.getFileUrl())
                            .ne(FileResource::getId, file.getId());
                    Long refCount = fileResourceMapper.selectCount(refWrapper);
                    if (refCount == 0) {
                        aliOSSUtils.deleteFile(file.getFileUrl());
                    }
                }
            } catch (Exception e) {
                log.error("删除OSS上的文件资源失败: {}", e.getMessage());
            }
        });
        // 5-再删除DB文件资源
        if (!fileList.isEmpty()) {
            List<Long> fileLongList = fileList.stream().map(FileResource::getId).toList();
            fileResourceMapper.deleteByIds(fileLongList);
            // 释放已用空间
            long totalSize = fileList.stream()
                    .filter(f -> f.getFileSize() != null)
                    .mapToLong(FileResource::getFileSize)
                    .sum();
            if (totalSize > 0) {
                userFileQuotaService.reduceUserSpace(userId, totalSize);
            }
        }
        // 6-删除DB文件夹
        fileFolderMapper.deleteByIds(folderList);
    }

    private List<Long> findAllChildFolderIds(Long parentFolderId, Long userId) {
        List<Long> folderIds = new ArrayList<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(parentFolderId);

        while (!queue.isEmpty()) {
            Long folderId = queue.poll();

            LambdaQueryWrapper<FileFolder> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FileFolder::getParentId, folderId);
            wrapper.eq(FileFolder::getUserId, userId);

            List<FileFolder> childFolders = fileFolderMapper.selectList(wrapper);
            childFolders.forEach(folder -> {
                queue.add(folder.getId());
                folderIds.add(folder.getId());
            });
            // 方式 2
            /*for(FileFolder folder: childFolders){
                queue.add(folder.getId());
                folderIds.add(folder.getId());
            }*/
        }
        return folderIds;
    }

    private List<FileResource> findAllChildFileIds(List<Long> folderId, Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(FileResource::getFolderId, folderId);
        wrapper.eq(FileResource::getUserId, userId);
        List<FileResource> fileResources = fileResourceMapper.selectList(wrapper);

        return fileResources;
    }

    /* 检查同级文件夹名称是否存在 */
    private boolean checkSameFolderNameExists(Long parentId, String folderName, Long userId) {
        LambdaQueryWrapper<FileFolder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileFolder::getUserId, userId);
        wrapper.eq(FileFolder::getParentId, parentId);
        wrapper.eq(FileFolder::getFolderName, folderName);
        return fileFolderMapper.selectCount(wrapper) > 0;
    }

    /* 检查文件夹是否属于当前用户 */
    private boolean checkFolderBelongsToUser(Long parentId, Long userId) {
        LambdaQueryWrapper<FileFolder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileFolder::getUserId, userId);
        wrapper.eq(FileFolder::getId, parentId);
        return fileFolderMapper.selectCount(wrapper) > 0;
    }

    /**
     * 移动文件到目标文件夹
     * 步骤：
     * 1. 校验文件是否存在且归属当前用户
     * 2. 校验目标文件夹（根目录跳过）
     * 3. 校验目标文件夹不是当前文件夹
     * 4. 校验目标文件夹下同名文件冲突（如果冲突，生成新文件名）
     * 5. 更新文件的 folderId
     * @param fileId
     * @param targetFolderId
     * @param userId
     */
    @Override
    @Transactional
    public void moveFile(Long fileId, Long targetFolderId, Long userId) {
        // 1. 校验文件是否存在且归属当前用户
        FileResource file = fileResourceMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        if (!file.getUserId().equals(userId)) {
            throw new BusinessException("无权移动该文件");
        }

        // 2. 校验目标文件夹（根目录跳过）
        if (targetFolderId != 0) {
            FileFolder targetFolder = fileFolderMapper.selectById(targetFolderId);
            if (targetFolder == null) {
                throw new BusinessException("目标文件夹不存在");
            }
            if (!targetFolder.getUserId().equals(userId)) {
                throw new BusinessException("目标文件夹不属于当前用户");
            }
        }

        // 3. 校验目标文件夹不是当前文件夹
        if (file.getFolderId().equals(targetFolderId)) {
            throw new BusinessException("文件已在目标文件夹中");
        }

        // 4. 校验目标文件夹下同名文件冲突
        if (checkFileNameInFolderConflict(file.getFileName(), targetFolderId, userId)) {
            // 生成新文件名
            String newName = generateUniqueFileName(file.getFileName(), targetFolderId, userId);
            file.setFileName(newName);
        }

        // 4. 更新文件的 folderId
//        UpdateWrapper<FileResource> wrapper = new UpdateWrapper<>();
//        wrapper.eq("id", fileId)
//                .set("folder_id", targetFolderId)
//                .set("update_time", LocalDateTime.now());
        LambdaUpdateWrapper<FileResource> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileResource::getId, fileId)
                .set(FileResource::getFolderId, targetFolderId)
                .set(FileResource::getUpdateTime, LocalDateTime.now());
        fileResourceMapper.update(null, wrapper);
    }

    private boolean checkFileNameInFolderConflict(String fileName, Long targetFolderId, Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getUserId, userId);
        wrapper.eq(FileResource::getFolderId, targetFolderId);
        wrapper.eq(FileResource::getFileName, fileName);
        return fileResourceMapper.selectCount(wrapper) > 0;
    }

    /**
     * 生成唯一文件名，避免文件覆盖（如果文件名已存在，则在文件名后添加数字后缀）
     * @param originalFileName 原始文件名
     * @param targetFolderId 目标文件夹ID
     * @param userId 用户ID
     * @return String 唯一文件名
     */
    private String generateUniqueFileName(String originalFileName, Long targetFolderId, Long userId) {
        if (originalFileName == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String nameMain;
        String extension = "";
        int lastDotIndex = originalFileName.lastIndexOf(".");
        if (lastDotIndex > 0) {
            nameMain = originalFileName.substring(0, lastDotIndex);
            extension = originalFileName.substring(lastDotIndex);
        } else {
            nameMain = originalFileName;
        }

        // 构造查询条件：匹配 "名称主体" 或 "名称主体(数字)" 开头的文件
        String prefix = nameMain + "(";
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getFolderId, targetFolderId)
                .eq(FileResource::getUserId, userId)
                .and(w -> w.likeRight(FileResource::getFileName, nameMain)
                        .or()
                        .likeRight(FileResource::getFileName, prefix));

        List<FileResource> list = fileResourceMapper.selectList(wrapper);
        if (list.isEmpty()) {
            return originalFileName;
        }

        // 提取最大后缀数字
        int maxNum = 0;
        for (FileResource res : list) {
            String fileName = res.getFileName();
            // 匹配 "名称主体(数字)扩展名" 格式
            // 使用 Pattern.quote() 转义文件名中的特殊字符，防止正则表达式错误
            String regex = Pattern.quote(nameMain) + "\\((\\d+)\\)" + Pattern.quote(extension);
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(fileName);
            if (matcher.matches()) {
                int num = Integer.parseInt(matcher.group(1));
                if (num > maxNum) {
                    maxNum = num;
                }
            }
        }
        return String.format("%s(%d)%s", nameMain, maxNum + 1, extension);
    }

    @Override
    public void moveFolder(Long id, Long targetParentId, Long userId) {
        // 1. 校验文件夹是否存在且归属当前用户
        FileFolder folder = fileFolderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new BusinessException("无权移动该文件夹");
        }

        // 2. 校验已在目标位置
        if (folder.getParentId().equals(targetParentId)) {
            throw new BusinessException("文件夹已在目标位置");
        }

        // 3. 校验目标父文件夹（根目录跳过）
        if (targetParentId != 0) {
            FileFolder targetParent = fileFolderMapper.selectById(targetParentId);
            if (targetParent == null) {
                throw new BusinessException("目标文件夹不存在");
            }
            if (!targetParent.getUserId().equals(userId)) {
                throw new BusinessException("目标文件夹不属于当前用户");
            }

            // 4. 防止移到自身或子文件夹中
            List<Long> childFolderIds = findAllChildFolderIds(id, userId);
            if (childFolderIds.contains(targetParentId)) {
                throw new BusinessException("不能将文件夹移动到自身或其子文件夹中");
            }
        }

        // 5. 检查目标位置同名冲突
        if (checkSameFolderNameExists(targetParentId, folder.getFolderName(), userId)) {
            throw new BusinessException("目标位置已存在同名文件夹");
        }

        // 6. 更新文件夹的 parentId
        /*UpdateWrapper<FileFolder> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .set("parent_id", targetParentId)
                .set("update_time", LocalDateTime.now());
        fileFolderMapper.update(null, wrapper);*/
        LambdaUpdateWrapper<FileFolder>wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(FileFolder::getId, id)
                .set(FileFolder::getParentId, targetParentId)
                .set(FileFolder::getUpdateTime, LocalDateTime.now());
        fileFolderMapper.update(null, wrapper);
    }

    @Override
    @Transactional
    public void batchMoveToFolder(FileFolderContentVO vo, Long userId) {
        Long targetFolderId = vo.getFolderId();

        // 1. 校验目标文件夹（根目录跳过）
        if (targetFolderId != null && targetFolderId != 0) {
            FileFolder targetFolder = fileFolderMapper.selectById(targetFolderId);
            if (targetFolder == null) {
                throw new BusinessException("目标文件夹不存在");
            }
            if (!targetFolder.getUserId().equals(userId)) {
                throw new BusinessException("目标文件夹不属于当前用户");
            }
        }

        // todo-当前文件夹folderId是List<FileFolderVO>的子文件夹列表或者等于目标文件夹则报错


        // 2. 批量移动文件夹
        if (vo.getFolders() != null) {
            for (FileFolderVO folderVO : vo.getFolders()) {
                moveFolder(folderVO.getFolderId(), targetFolderId, userId);
            }
        }

        // 3. 批量移动文件
        if (vo.getFiles() != null) {
            for (FileResourceVO fileVO : vo.getFiles()) {
                moveFile(fileVO.getId(), targetFolderId, userId);
            }
        }
    }

    // ==================== 文件夹导出 ====================

    @Override
    public String exportFolder(Long folderId, Long userId) {
        // 1. 并发锁检查：每用户最多 1 个导出任务
        String lockKey = EXPORT_LOCK_KEY + userId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", TASK_TTL_HOURS, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(locked)) {
            throw new BusinessException(ResultCode.EXPORT_TASK_EXISTS.getMsg());
        }

        // 2. 校验文件夹存在且属于当前用户
        FileFolder folder = null;
        String folderName = "根目录";
        if (folderId != 0) {
            folder = fileFolderMapper.selectById(folderId);
            if (folder == null) {
                releaseExportLock(userId);
                throw new BusinessException("文件夹不存在");
            }
            if (!folder.getUserId().equals(userId)) {
                releaseExportLock(userId);
                throw new BusinessException("无权访问该文件夹");
            }
            folderName = folder.getFolderName();
        }

        // 3. BFS 收集所有子孙文件夹 ID
        List<Long> allFolderIds = new ArrayList<>();
        allFolderIds.add(folderId);
        if (folderId != 0) {
            allFolderIds.addAll(findAllChildFolderIds(folderId, userId));
        }

        // 4. 查询所有文件
        List<FileResource> allFiles = findAllChildFileIds(allFolderIds, userId);
        if (allFiles.isEmpty()) {
            releaseExportLock(userId);
            throw new BusinessException(ResultCode.FOLDER_EMPTY.getMsg());
        }

        // 5. 计算总大小
        long totalBytes = allFiles.stream()
                .filter(f -> f.getFileSize() != null)
                .mapToLong(FileResource::getFileSize)
                .sum();

        // 6. 生成 taskId 并写 Redis
        String taskId = String.valueOf(IdUtil.getSnowflakeNextId());
        FolderExportTask task = new FolderExportTask();
        task.setTaskId(taskId);
        task.setUserId(userId);
        task.setFolderId(folderId);
        task.setFolderName(folderName);
        task.setStatus(FolderExportTask.STATUS_PENDING);
        task.setTotalFiles(allFiles.size());
        task.setTotalBytes(totalBytes);
        task.setCreatedAt(LocalDateTime.now().toString());

        String taskKey = EXPORT_TASK_KEY + taskId;
        redisTemplate.opsForValue().set(taskKey, task.toJson(), TASK_TTL_HOURS, TimeUnit.HOURS);

        // 7. 通过 ApplicationContext 获取代理以触发 @Async
        applicationContext.getBean(FileFolderServiceImpl.class).executeExport(taskId);

        return taskId;
    }

    /**
     * 异步执行压缩导出（由 Spring AOP 代理调用以启用 @Async）
     */
    @Async("exportExecutor")
    public void executeExport(String taskId) {
        String taskKey = EXPORT_TASK_KEY + taskId;
        FolderExportTask task = readTaskFromRedis(taskKey);
        if (task == null) return;

        Long userId = task.getUserId();
        String ossObjectKey = "yun/temp/" + userId + "/" + taskId + ".zip";
        String uploadId = null;
        List<com.aliyun.oss.model.PartETag> partETags = new java.util.ArrayList<>();
        int partNumber = 1;

        try {
            // 1. 设置 PROCESSING
            task.setStatus(FolderExportTask.STATUS_PROCESSING);
            updateTaskInRedis(taskKey, task);

            // 2. BFS 收集文件夹和文件
            List<Long> allFolderIds = new ArrayList<>();
            allFolderIds.add(task.getFolderId());
            if (task.getFolderId() != 0) {
                allFolderIds.addAll(findAllChildFolderIds(task.getFolderId(), userId));
            }
            List<FileResource> allFiles = findAllChildFileIds(allFolderIds, userId);

            // 3. 构建 folderId → 相对路径 映射
            Map<Long, String> folderPathMap = buildFolderPathMap(allFolderIds, task.getFolderId(), task.getFolderName());

            // 4. 初始化 OSS 分片上传
            uploadId = aliOSSUtils.initiateMultipartUpload(ossObjectKey);

            // 5. 逐文件压缩并分片上传
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUFFER_SIZE);
            ZipOutputStream zos = new ZipOutputStream(buffer);

            for (FileResource file : allFiles) {
                // 检查取消标志
                FolderExportTask current = readTaskFromRedis(taskKey);
                if (current != null && FolderExportTask.STATUS_CANCELLED.equals(current.getStatus())) {
                    // 取消：清理 OSS 分片、Redis、锁
                    if (uploadId != null) {
                        aliOSSUtils.abortMultipartUpload(ossObjectKey, uploadId);
                    }
                    redisTemplate.delete(taskKey);
                    releaseExportLock(userId);
                    zos.close();
                    return;
                }

                // 下载文件流
                if (file.getFileUrl() == null || file.getFileUrl().isEmpty()) {
                    continue;
                }

                try (InputStream fileStream = aliOSSUtils.downloadFile(file.getFileUrl())) {
                    // 构建 zip 内路径
                    String folderPath = folderPathMap.getOrDefault(file.getFolderId(), "");
                    String entryName;
                    if (folderPath.isEmpty()) {
                        entryName = task.getFolderName() + "/" + file.getFileName();
                    } else {
                        entryName = task.getFolderName() + "/" + folderPath + "/" + file.getFileName();
                    }

                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);

                    byte[] readBuf = new byte[8192];
                    int len;
                    while ((len = fileStream.read(readBuf)) != -1) {
                        zos.write(readBuf, 0, len);
                        // 缓冲区满了就上传一个分片
                        if (buffer.size() >= BUFFER_SIZE) {
                            zos.closeEntry();
                            zos.flush();
                            com.aliyun.oss.model.PartETag partETag =
                                    aliOSSUtils.uploadPart(ossObjectKey, uploadId, partNumber,
                                            buffer.toByteArray());
                            partETags.add(partETag);
                            partNumber++;
                            buffer.reset();
                        }
                    }
                    zos.closeEntry();
                } catch (Exception e) {
                    log.warn("跳过无法下载的文件: fileId={}, fileName={}, error={}",
                            file.getId(), file.getFileName(), e.getMessage());
                }

                // 更新 Redis 进度
                task.setProcessedFiles(task.getProcessedFiles() + 1);
                if (file.getFileSize() != null) {
                    task.setProcessedBytes(task.getProcessedBytes() + file.getFileSize());
                }
                updateTaskInRedis(taskKey, task);
            }

            // 6. 刷新剩余缓冲区
            zos.flush();
            zos.close();
            if (buffer.size() > 0) {
                com.aliyun.oss.model.PartETag partETag =
                        aliOSSUtils.uploadPart(ossObjectKey, uploadId, partNumber, buffer.toByteArray());
                partETags.add(partETag);
            }

            // 7. 合并分片
            if (partETags.isEmpty()) {
                // 没有文件或所有文件都跳过了
                aliOSSUtils.abortMultipartUpload(ossObjectKey, uploadId);
                task.setStatus(FolderExportTask.STATUS_FAILED);
                task.setErrorMessage("没有可下载的文件");
                updateTaskInRedis(taskKey, task);
            } else {
                aliOSSUtils.completeMultipartUpload(ossObjectKey, uploadId, partETags);
                task.setStatus(FolderExportTask.STATUS_COMPLETED);
                task.setOssObjectKey(ossObjectKey);
                updateTaskInRedis(taskKey, task);
            }

        } catch (Exception e) {
            log.error("导出文件夹失败, taskId={}", taskId, e);
            // 异常：标记 FAILED + 清理 OSS 分片
            if (uploadId != null) {
                try {
                    aliOSSUtils.abortMultipartUpload(ossObjectKey, uploadId);
                } catch (Exception ignored) {}
            }
            task.setStatus(FolderExportTask.STATUS_FAILED);
            task.setErrorMessage(e.getMessage());
            updateTaskInRedis(taskKey, task);
        } finally {
            // 释放并发锁
            releaseExportLock(userId);
        }
    }

    @Override
    public FolderExportTask getExportProgress(String taskId, Long userId) {
        FolderExportTask task = readTaskFromRedis(EXPORT_TASK_KEY + taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.EXPORT_TASK_NOT_FOUND.getMsg());
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该任务");
        }
        return task;
    }

    @Override
    public void cancelExport(String taskId, Long userId) {
        FolderExportTask task = readTaskFromRedis(EXPORT_TASK_KEY + taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.EXPORT_TASK_NOT_FOUND.getMsg());
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该任务");
        }
        // 仅处理中的任务可以取消
        if (FolderExportTask.STATUS_PROCESSING.equals(task.getStatus())
                || FolderExportTask.STATUS_PENDING.equals(task.getStatus())) {
            task.setStatus(FolderExportTask.STATUS_CANCELLED);
            updateTaskInRedis(EXPORT_TASK_KEY + taskId, task);
        }
    }

    @Override
    public String getExportDownloadUrl(String taskId, Long userId) {
        FolderExportTask task = readTaskFromRedis(EXPORT_TASK_KEY + taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.EXPORT_TASK_NOT_FOUND.getMsg());
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该任务");
        }
        if (!FolderExportTask.STATUS_COMPLETED.equals(task.getStatus())) {
            throw new BusinessException("任务尚未完成，无法下载");
        }
        // 生成 5 分钟预签名 URL
        return aliOSSUtils.getSignedUrlWithExpiry(task.getOssObjectKey(), 300_000);
    }

    // ==================== 导出私有辅助方法 ====================

    private FolderExportTask readTaskFromRedis(String taskKey) {
        String json = redisTemplate.opsForValue().get(taskKey);
        if (json == null) return null;
        try {
            return FolderExportTask.fromJson(json);
        } catch (Exception e) {
            log.error("反序列化导出任务失败, key={}", taskKey, e);
            return null;
        }
    }

    private void updateTaskInRedis(String taskKey, FolderExportTask task) {
        redisTemplate.opsForValue().set(taskKey, task.toJson(), TASK_TTL_HOURS, TimeUnit.HOURS);
    }

    private void releaseExportLock(Long userId) {
        redisTemplate.delete(EXPORT_LOCK_KEY + userId);
    }

    /**
     * 构建 folderId → 相对路径 的映射（从根文件夹名称下一级开始）
     */
    private Map<Long, String> buildFolderPathMap(List<Long> folderIds, Long rootFolderId, String rootFolderName) {
        Map<Long, String> pathMap = new HashMap<>();
        // 根目录本身不设路径
        for (Long folderId : folderIds) {
            if (folderId.equals(rootFolderId)) continue;
            FileFolder folder = fileFolderMapper.selectById(folderId);
            if (folder == null) continue;
            List<String> segments = new ArrayList<>();
            Long currentParentId = folder.getParentId();
            while (currentParentId != null && currentParentId != 0 && !currentParentId.equals(rootFolderId)) {
                FileFolder parent = fileFolderMapper.selectById(currentParentId);
                if (parent != null) {
                    segments.add(0, parent.getFolderName());
                    currentParentId = parent.getParentId();
                } else {
                    break;
                }
            }
            if (!segments.isEmpty()) {
                pathMap.put(folderId, String.join("/", segments));
            }
        }
        return pathMap;
    }
}
