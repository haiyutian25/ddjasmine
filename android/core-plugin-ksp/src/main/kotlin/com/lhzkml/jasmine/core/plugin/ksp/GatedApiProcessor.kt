package com.lhzkml.jasmine.core.plugin.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter

/**
 * Generates `<Interface>Gated` wrappers for interfaces whose methods carry
 * `@GatedApi`. The wrapper's suspend methods ask the charter first and
 * delegate only on Allow — the permission check can never be forgotten at a
 * call site, because the checked entry point is the generated one.
 */
class GatedApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val functions = resolver
            .getSymbolsWithAnnotation(GATED_API)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()
        val invalid = functions.filterNot { it.validate() }
        functions
            .filter { it.validate() }
            .groupBy { it.parentDeclaration as? KSClassDeclaration }
            .forEach { (iface, methods) ->
                if (iface == null) {
                    logger.error("@GatedApi 只能标注接口成员方法", methods.first())
                    return@forEach
                }
                generate(iface, methods)
            }
        return invalid
    }

    private fun generate(iface: KSClassDeclaration, methods: List<KSFunctionDeclaration>) {
        val packageName = iface.packageName.asString()
        val ifaceName = iface.simpleName.asString()
        val gatedName = "${ifaceName}Gated"
        val file = codeGenerator.createNewFile(
            Dependencies(false, iface.containingFile!!),
            packageName,
            gatedName,
        )
        OutputStreamWriter(file, Charsets.UTF_8).use { out ->
            out.write("package $packageName\n\n")
            out.write("import com.lhzkml.jasmine.core.plugin.ApiRule\n")
            out.write("import com.lhzkml.jasmine.core.plugin.PluginHost\n\n")
            out.write("/** 由 KSP 生成：[$ifaceName] 的权限织入包装。 */\n")
            out.write("class $gatedName(\n")
            out.write("    private val delegate: $ifaceName,\n")
            out.write("    private val callerPluginId: String?,\n")
            out.write(")\n")
            out.write("{\n")
            for (method in methods) {
                writeMethod(out, ifaceName, method)
            }
            out.write("}\n")
        }
        logger.info("已生成 $packageName.$gatedName（${methods.size} 个织入方法）")
    }

    private fun writeMethod(
        out: OutputStreamWriter,
        ifaceName: String,
        method: KSFunctionDeclaration,
    ) {
        val annotation = method.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == GATED_API
        }
        val args = annotation.arguments.associate { it.name?.asString() to it.value }
        val rule = args["rule"]?.toString()?.substringAfterLast('.') ?: "Host"
        val hardFail = args["hardFail"] as? Boolean ?: false
        val targetParam = args["targetParam"] as? String ?: ""

        val name = method.simpleName.asString()
        val params = method.parameters
        val signature = params.joinToString(", ") {
            "${it.name!!.asString()}: ${it.type.resolve().declaration.qualifiedName?.asString() ?: it.type.resolve().declaration.simpleName.asString()}"
        }
        val callArgs = params.joinToString(", ") { it.name!!.asString() }
        val returnType = method.returnType?.resolve()
        val returnsUnit = returnType == null || returnType.declaration.qualifiedName?.asString() == "kotlin.Unit"
        val returnClause = if (returnsUnit) "" else ": ${returnType!!.declaration.qualifiedName?.asString() ?: returnType.declaration.simpleName.asString()}"
        val permissionKey = "$ifaceName.$name"
        val targetExpr = if (targetParam.isNotEmpty()) targetParam else "\"\""

        out.write("\n")
        out.write("    /** 织入 [$permissionKey]：规则 $rule，hardFail=$hardFail。 */\n")
        out.write("    suspend fun $name($signature)$returnClause {\n")
        out.write("        val granted = PluginHost.checkApi(\n")
        out.write("            ApiRule.$rule, callerPluginId, $targetExpr, \"$permissionKey\", $hardFail,\n")
        out.write("        )\n")
        out.write("        if (!granted) throw SecurityException(\"API 访问被拒绝: $permissionKey\")\n")
        if (returnsUnit) {
            out.write("        delegate.$name($callArgs)\n")
        } else {
            out.write("        return delegate.$name($callArgs)\n")
        }
        out.write("    }\n")
    }

    private companion object {
        const val GATED_API = "com.lhzkml.jasmine.core.plugin.GatedApi"
    }
}

class GatedApiProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        GatedApiProcessor(environment.codeGenerator, environment.logger)
}
