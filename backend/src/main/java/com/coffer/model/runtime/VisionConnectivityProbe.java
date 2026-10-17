package com.coffer.model.runtime;

import dev.langchain4j.data.message.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Synthetic pixels only. Acceptance tests still need a separate business image fixture. */
final class VisionConnectivityProbe {
    static UserMessage message() {
        try {
            var pixels = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            for (int x=0; x<32; x++) for (int y=0; y<32; y++) pixels.setRGB(x,y,0xff0000);
            var output = new ByteArrayOutputStream();
            ImageIO.write(pixels, "png", output);
            return UserMessage.from(ImageContent.from(Base64.getEncoder().encodeToString(output.toByteArray()), "image/png"),
                    TextContent.from("Name the color of this synthetic test image in one word."));
        } catch (java.io.IOException impossible) { throw new IllegalStateException("测试图片生成失败"); }
    }
}
