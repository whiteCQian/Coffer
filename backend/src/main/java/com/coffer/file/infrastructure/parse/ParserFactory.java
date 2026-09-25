package com.coffer.file.infrastructure.parse;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 解析器工厂：依据文件扩展名分发到对应的 {@link DocumentParser}。
 *
 * <p>构造器注入所有 {@link DocumentParser} 实现（TxtParser、PdfParser、WordParser），
 * 启动时按 {@link DocumentParser#getFileExtension()} 建立扩展名到解析器的映射。
 */
@Component
public class ParserFactory {

    private final List<DocumentParser> parsers;
    private final Map<String, DocumentParser> parserMap = new ConcurrentHashMap<>();

    public ParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 遍历注入的解析器，以扩展名（小写）为 key 建立映射。
     */
    @PostConstruct
    public void init() {
        for (DocumentParser parser : parsers) {
            String ext = parser.getFileExtension();
            if (ext != null && !ext.isBlank()) {
                parserMap.put(ext.toLowerCase(Locale.ROOT), parser);
            }
        }
        // WordParser 同时兼容旧版 .doc：getFileExtension() 只返回 docx，此处补充 doc 别名
        DocumentParser wordParser = parserMap.get("docx");
        if (wordParser != null) {
            parserMap.put("doc", wordParser);
        }
    }

    /**
     * 根据文件扩展名获取对应解析器。
     *
     * @param fileExtension 文件扩展名（不含点号，大小写不敏感）
     * @return 对应解析器
     * @throws IllegalArgumentException 扩展名为空或未匹配到解析器
     */
    public DocumentParser getParser(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileExtension);
        }
        DocumentParser parser = parserMap.get(fileExtension.trim().toLowerCase(Locale.ROOT));
        if (parser == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileExtension);
        }
        return parser;
    }

    /**
     * 返回所有支持的扩展名列表（升序），供前端校验。
     *
     * @return 扩展名列表
     */
    public List<String> getSupportedExtensions() {
        return parserMap.keySet().stream().sorted().collect(Collectors.toList());
    }
}
