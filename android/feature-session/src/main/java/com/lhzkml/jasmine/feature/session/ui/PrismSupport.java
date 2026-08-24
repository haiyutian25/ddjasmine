package com.lhzkml.jasmine.feature.session.ui;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

/**
 * prism4j-bundler 的 javac 注解处理器据此生成 PrismGrammarLocator（与本类同包）。
 * 必须用 Java 编写：处理器运行在 javac 阶段，Kotlin 编译更早，看不到生成类。
 * 语言名必须用真实名（javascript 而非 js、markup 而非 xml）。
 */
@PrismBundle(
        include = {
                "markup", "css", "clike", "javascript", "json", "java",
                "kotlin", "python", "c", "cpp", "csharp", "go", "groovy",
                "sql", "yaml", "markdown", "dart", "swift", "git", "makefile"
        },
        grammarLocatorClassName = ".PrismGrammarLocator"
)
public final class PrismSupport {

    private PrismSupport() {
    }

    public static Prism4j create() {
        return new Prism4j(new PrismGrammarLocator());
    }
}
