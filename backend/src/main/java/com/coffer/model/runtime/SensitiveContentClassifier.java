package com.coffer.model.runtime;

import com.coffer.file.domain.FileMetadata;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative local rules. Unknown content is never interpreted as safe. */
@Component
public class SensitiveContentClassifier {
    public enum Risk { CLEAR, SENSITIVE, UNKNOWN }
    private static final Pattern NAME = Pattern.compile(
            "身份证|护照|银行卡|银行|财务|薪资|工资|税务|合同|密码|私钥|密钥|secret|token|credential|private.?key|passport|payroll|invoice",
            Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> BODY = List.of(
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(?:api[_ -]?key|access[_ -]?token|client[_ -]?secret|password|密码|密钥)\\s*[:=：]\\s*[^\\s]{6,}"),
            Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~-]{16,}\\b"),
            Pattern.compile("(?i)(?:身份证|护照|证件号|银行卡|银行账户|对公账户|工资|薪资|薪酬|税额|税号|发票|财务报表|invoice|payroll|salary|tax.?id|account.?number)"),
            Pattern.compile("(?:^|[^0-9])\\d{17}[0-9Xx](?:$|[^0-9])"),
            Pattern.compile("(?:^|[^0-9])(?:\\d[ -]?){13,19}(?:$|[^0-9])"));

    public Risk classify(FileMetadata file, String text, boolean locallyParsed) {
        String name = file.getFileName() == null ? "" : file.getFileName();
        if (NAME.matcher(name).find()) return Risk.SENSITIVE;
        if (!locallyParsed || text == null) return Risk.UNKNOWN;
        for (Pattern pattern : BODY) if (pattern.matcher(text).find()) return Risk.SENSITIVE;
        return Risk.CLEAR;
    }
}
