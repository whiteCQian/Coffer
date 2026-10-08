package com.coffer.file.application.parse;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal child JVM entry point: no Spring context, database or storage credentials. */
public final class ParserWorkerMain {
    private ParserWorkerMain() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length != 3) return 2;
        try {
            System.setProperty("java.awt.headless", "true");
            javax.imageio.ImageIO.setUseCache(false);
            Long fileId = "-".equals(args[0]) ? null : Long.valueOf(args[0]);
            long revision = Long.parseLong(args[1]);
            var document = new BoundedDocumentParser().parse(fileId, revision, args[2], System.in);
            new ObjectMapper().writeValue(System.out, document);
            System.out.flush();
            return 0;
        } catch (Throwable failure) {
            // stderr is discarded by the parent; never print a source filename or body.
            return 2;
        }
    }
}
