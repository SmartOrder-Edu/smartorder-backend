package com.smartorder.edu.web;

import java.io.File;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FolderNameUtil {

    private static final String DEFAULT_NAME = "新建文件夹";

    /**
     * 生成可用文件夹名称，并实际创建该文件夹
     * @param parentPath 父目录路径
     * @param inputName  用户输入（为空则默认"新建文件夹"）
     * @return 最终创建成功的文件夹名
     */
    public static String createFolder(String parentPath, String inputName) {
        String baseName = (inputName == null || inputName.trim().isEmpty()) 
                        ? DEFAULT_NAME 
                        : inputName.trim();

        File parentDir = new File(parentPath);
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        String finalName = generateUniqueName(parentDir, baseName);
        File newFolder = new File(parentDir, finalName);
        boolean success = newFolder.mkdirs();
        
        return success ? finalName : null;
    }

    /**
     * 纯生成名字（不创建），用于预览或业务校验
     */
    public static String generateFolderName(String parentPath, String inputName) {
        String baseName = (inputName == null || inputName.trim().isEmpty()) 
                        ? DEFAULT_NAME 
                        : inputName.trim();
        File parentDir = new File(parentPath);
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        return generateUniqueName(parentDir, baseName);
    }

    private static String generateUniqueName(File parentDir, String baseName) {
        File[] files = parentDir.listFiles();
        
        // 如果目录为空或无法读取，直接返回基础名
        if (files == null || files.length == 0) {
            return baseName;
        }

        Set<String> existingNames = Stream.of(files)
                .map(File::getName)
                .collect(Collectors.toSet());

        // 不重名直接返回
        if (!existingNames.contains(baseName)) {
            return baseName;
        }

        // 找最大编号
        int maxNum = 0;
        String patternStr = "^" + Pattern.quote(baseName) + " \\((\\d+)\\)$";
        Pattern pattern = Pattern.compile(patternStr);

        for (String name : existingNames) {
            Matcher m = pattern.matcher(name);
            if (m.find()) {
                maxNum = Math.max(maxNum, Integer.parseInt(m.group(1)));
            }
        }
        return baseName + " (" + (maxNum + 1) + ")";
    }

    public static void main(String[] args) {
        String parent = "D:/test";
        
        // 现在会实际创建文件夹，第二次调用才能检测到第一次的结果
        System.out.println(createFolder(parent, ""));      // 新建文件夹
        System.out.println(createFolder(parent, ""));      // 新建文件夹 (1)
        System.out.println(createFolder(parent, ""));      // 新建文件夹 (2)
        
        // 也可以测试用户输入自定义名称
        System.out.println(createFolder(parent, "我的资料"));  // 我的资料
        System.out.println(createFolder(parent, "我的资料"));  // 我的资料 (1)
        System.out.println(createFolder(parent, ""));      // 新建文件夹 (2)
    }
}