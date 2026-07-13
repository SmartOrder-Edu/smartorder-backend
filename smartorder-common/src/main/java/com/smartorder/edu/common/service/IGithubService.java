package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.vo.GithubUserVO;

/**
 * GitHub 璁よ瘉涓庤皟鐢ㄦ湇鍔℃帴鍙? * @author Maq
 * @since 2026/07/12
 */
public interface IGithubService {
    /**
     * 鏍规嵁鎺堟潈鐮?code 鍚?GitHub 鎹㈠彇鐢ㄦ埛淇℃伅
     * @param code 鎺堟潈鐮?     * @return GitHub 鐢ㄦ埛淇℃伅
     */
    GithubUserVO getGithubUser(String code);
}
