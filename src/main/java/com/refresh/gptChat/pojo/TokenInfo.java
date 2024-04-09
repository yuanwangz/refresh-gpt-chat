package com.refresh.gptChat.pojo;

import com.refresh.gptChat.service.tokenService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        List<TokenInfo> tokens = refreshTokenList.get(key);

        // 如果tokens为null，抛出异常
        if (tokens == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "找不到有效的token，密钥为: " + key);
        }

        for (TokenInfo tokenInfo : tokens) {
            // 如果存在旧的Token且与当前token相同，则将其状态设置为"0"
            if (oldTokenInfo != null && oldTokenInfo.equals(tokenInfo)) {
                tokenInfo.setStatus("0");
            }

            // 如果AccessToken非空且status为"1"，则直接返回该TokenInfo
            if (!StringUtils.isEmpty(tokenInfo.getAccessToken()) && "1".equals(tokenInfo.getStatus())) {
                return tokenInfo;
            }

            // 如果RefreshToken非空，则尝试获取新的AccessToken
            if (!StringUtils.isEmpty(tokenInfo.getRefreshToken())) {
                String newAccessToken = tokenService.getAccessToken(tokenInfo.getRefreshToken());

                // 如果新的AccessToken非空，则更新tokenInfo的AccessToken和status，然后返回
                if (!StringUtils.isEmpty(newAccessToken)) {
                    tokenInfo.setAccessToken(newAccessToken);
                    tokenInfo.setStatus("1");
                    return tokenInfo;
                }
            }
        }

        // 所有的Token都无效，返回null
        return null;
    }


}


