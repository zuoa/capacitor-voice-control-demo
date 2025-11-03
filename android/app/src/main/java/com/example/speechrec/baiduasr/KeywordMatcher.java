package com.example.speechrec.baiduasr;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 关键词匹配引擎
 * 支持精确匹配、模糊匹配、正则表达式匹配
 */
public class KeywordMatcher {
    
    public enum MatchMode {
        EXACT,      // 精确匹配
        FUZZY,      // 模糊匹配（包含关键词即可）
        REGEX,      // 正则表达式匹配
        PHONETIC    // 语音相似度匹配
    }
    
    private List<KeywordPattern> patterns = new ArrayList<>();
    private boolean enabled = false;
    private MatchMode defaultMode = MatchMode.FUZZY;
    private float confidenceThreshold = 0.6f; // 置信度阈值
    
    public static class KeywordPattern {
        public String keyword;          // 关键词
        public String action;           // 匹配后触发的动作
        public MatchMode mode;          // 匹配模式
        public Pattern regexPattern;    // 正则表达式（如果mode是REGEX）
        public List<String> aliases;    // 别名/近音词
        public Map<String, Object> metadata; // 额外元数据
        
        public KeywordPattern(String keyword, String action, MatchMode mode) {
            this.keyword = keyword;
            this.action = action;
            this.mode = mode;
            this.aliases = new ArrayList<>();
            this.metadata = new HashMap<>();
            
            if (mode == MatchMode.REGEX) {
                this.regexPattern = Pattern.compile(keyword);
            }
        }
    }
    
    public static class MatchResult {
        public boolean matched;
        public String matchedKeyword;
        public String action;
        public float confidence;
        public String originalText;
        public Map<String, Object> metadata;
        
        public MatchResult(boolean matched) {
            this.matched = matched;
            this.metadata = new HashMap<>();
        }
        
        public JSObject toJSObject() {
            JSObject obj = new JSObject();
            obj.put("matched", matched);
            obj.put("matchedKeyword", matchedKeyword);
            obj.put("action", action);
            obj.put("confidence", confidence);
            obj.put("originalText", originalText);
            obj.put("metadata", new JSONObject(metadata));
            return obj;
        }
    }
    
    /**
     * 设置关键词列表
     */
    public void setKeywords(JSONArray keywordsArray) throws JSONException {
        patterns.clear();
        
        for (int i = 0; i < keywordsArray.length(); i++) {
            JSONObject kw = keywordsArray.getJSONObject(i);
            String keyword = kw.getString("keyword");
            String action = kw.optString("action", keyword);
            String modeStr = kw.optString("mode", "FUZZY");
            
            MatchMode mode;
            try {
                mode = MatchMode.valueOf(modeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                mode = defaultMode;
            }
            
            KeywordPattern pattern = new KeywordPattern(keyword, action, mode);
            
            // 添加别名
            if (kw.has("aliases")) {
                JSONArray aliasesArray = kw.getJSONArray("aliases");
                for (int j = 0; j < aliasesArray.length(); j++) {
                    pattern.aliases.add(aliasesArray.getString(j));
                }
            }
            
            // 添加元数据
            if (kw.has("metadata")) {
                JSONObject meta = kw.getJSONObject("metadata");
                pattern.metadata.put("raw", meta.toString());
            }
            
            patterns.add(pattern);
        }
        
        enabled = !patterns.isEmpty();
    }
    
    /**
     * 匹配文本
     */
    public MatchResult match(String text) {
        MatchResult result = new MatchResult(false);
        result.originalText = text;
        
        if (!enabled || text == null || text.isEmpty()) {
            return result;
        }
        
        // 规范化文本：移除空格和标点
        String normalizedText = normalizeText(text);
        
        float bestConfidence = 0f;
        KeywordPattern bestMatch = null;
        
        // 遍历所有关键词模式
        for (KeywordPattern pattern : patterns) {
            float confidence = matchPattern(pattern, text, normalizedText);
            
            if (confidence > bestConfidence && confidence >= confidenceThreshold) {
                bestConfidence = confidence;
                bestMatch = pattern;
            }
        }
        
        if (bestMatch != null) {
            result.matched = true;
            result.matchedKeyword = bestMatch.keyword;
            result.action = bestMatch.action;
            result.confidence = bestConfidence;
            result.metadata = new HashMap<>(bestMatch.metadata);
        }
        
        return result;
    }
    
    /**
     * 匹配单个模式
     */
    private float matchPattern(KeywordPattern pattern, String originalText, String normalizedText) {
        switch (pattern.mode) {
            case EXACT:
                return matchExact(pattern, normalizedText);
            case FUZZY:
                return matchFuzzy(pattern, normalizedText);
            case REGEX:
                return matchRegex(pattern, originalText);
            case PHONETIC:
                return matchPhonetic(pattern, normalizedText);
            default:
                return 0f;
        }
    }
    
    /**
     * 精确匹配
     */
    private float matchExact(KeywordPattern pattern, String text) {
        String normalizedKeyword = normalizeText(pattern.keyword);
        
        if (text.equals(normalizedKeyword)) {
            return 1.0f;
        }
        
        // 检查别名
        for (String alias : pattern.aliases) {
            if (text.equals(normalizeText(alias))) {
                return 0.95f; // 别名匹配稍低一点置信度
            }
        }
        
        return 0f;
    }
    
    /**
     * 模糊匹配（包含即可）
     */
    private float matchFuzzy(KeywordPattern pattern, String text) {
        String normalizedKeyword = normalizeText(pattern.keyword);
        
        if (text.contains(normalizedKeyword)) {
            // 计算匹配度：关键词长度 / 文本长度
            float ratio = (float) normalizedKeyword.length() / text.length();
            return Math.min(0.95f, 0.7f + ratio * 0.3f);
        }
        
        // 检查别名
        for (String alias : pattern.aliases) {
            String normalizedAlias = normalizeText(alias);
            if (text.contains(normalizedAlias)) {
                float ratio = (float) normalizedAlias.length() / text.length();
                return Math.min(0.90f, 0.65f + ratio * 0.3f);
            }
        }
        
        return 0f;
    }
    
    /**
     * 正则表达式匹配
     */
    private float matchRegex(KeywordPattern pattern, String text) {
        if (pattern.regexPattern == null) {
            return 0f;
        }
        
        Matcher matcher = pattern.regexPattern.matcher(text);
        if (matcher.find()) {
            // 基于匹配的长度计算置信度
            int matchLength = matcher.end() - matcher.start();
            float ratio = (float) matchLength / text.length();
            return Math.min(0.95f, 0.7f + ratio * 0.3f);
        }
        
        return 0f;
    }
    
    /**
     * 语音相似度匹配（简单实现：基于编辑距离）
     */
    private float matchPhonetic(KeywordPattern pattern, String text) {
        String normalizedKeyword = normalizeText(pattern.keyword);
        
        // 计算编辑距离
        int distance = levenshteinDistance(text, normalizedKeyword);
        int maxLen = Math.max(text.length(), normalizedKeyword.length());
        
        if (maxLen == 0) return 0f;
        
        // 转换为相似度 (1 - 归一化距离)
        float similarity = 1.0f - ((float) distance / maxLen);
        
        // 只有相似度超过一定阈值才认为匹配
        if (similarity >= 0.7f) {
            return similarity * 0.9f; // 降低一点置信度
        }
        
        // 检查别名
        for (String alias : pattern.aliases) {
            String normalizedAlias = normalizeText(alias);
            distance = levenshteinDistance(text, normalizedAlias);
            maxLen = Math.max(text.length(), normalizedAlias.length());
            similarity = 1.0f - ((float) distance / maxLen);
            
            if (similarity >= 0.7f) {
                return similarity * 0.85f;
            }
        }
        
        return 0f;
    }
    
    /**
     * 规范化文本：转小写、移除空格和标点
     */
    private String normalizeText(String text) {
        if (text == null) return "";
        // 移除标点和空格，保留中文、英文、数字
        return text.toLowerCase()
                   .replaceAll("[\\p{Punct}\\s]+", "")
                   .trim();
    }
    
    /**
     * 计算编辑距离（Levenshtein Distance）
     */
    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        
        int[][] dp = new int[len1 + 1][len2 + 1];
        
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                );
            }
        }
        
        return dp[len1][len2];
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = Math.max(0f, Math.min(1f, threshold));
    }
    
    public void setDefaultMode(MatchMode mode) {
        this.defaultMode = mode;
    }
}

