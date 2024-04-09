package com.refresh.gptChat.pojo;

import com.refresh.gptChat.service.tokenService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TokenInfo {
    private String refreshToken;
    private String accessToken;
    private String requestId;
    private String status;

    public static TokenInfo getAccess_token(String key, ConcurrentHashMap<String, List<TokenInfo>> refreshTokenList, tokenService tokenService, TokenInfo oldTokenInfo) {
        List<TokenInfo> tokensList = refreshTokenList.get(key);

        // 如果tokens为null，抛出异常
        if (tokensList == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "找不到有效的token，密钥为: " + key);
        }

        LinkedList<TokenInfo> tokensQueue = new LinkedList<>(tokensList);
        int tokenCount = tokensQueue.size();

        while (tokenCount-- > 0) {
            TokenInfo tokenInfo = tokensQueue.poll();

            // 如果存在旧的Token且与当前token相同，则将其状态设置为"0"
            if (oldTokenInfo != null && oldTokenInfo.equals(tokenInfo)) {
                tokenInfo.setStatus("0");
            }

            // 如果AccessToken非空且status为"1"，则直接返回该TokenInfo
            else if (!StringUtils.isEmpty(tokenInfo.getAccessToken()) && "1".equals(tokenInfo.getStatus())) {
                tokensQueue.offer(tokenInfo);
                refreshTokenList.put(key, new ArrayList<>(tokensQueue));
                return tokenInfo;
            }

            // 如果RefreshToken非空，则尝试获取新的AccessToken
            else if (!StringUtils.isEmpty(tokenInfo.getRefreshToken())) {
                String newAccessToken = tokenService.getAccessToken(tokenInfo.getRefreshToken());

                // 如果新的AccessToken非空，则更新tokenInfo的AccessToken和status
                if (!StringUtils.isEmpty(newAccessToken)) {
                    tokenInfo.setAccessToken(newAccessToken);
                    tokenInfo.setStatus("1");
                    tokensQueue.offer(tokenInfo);
                    refreshTokenList.put(key, new ArrayList<>(tokensQueue));
                    return tokenInfo;
                }
            }

            tokensQueue.offer(tokenInfo);
        }

        refreshTokenList.put(key, new ArrayList<>(tokensQueue));

        // 所有的Token都无效，返回null
        return null;
    }



}


