# 用户工作区中另一个未提交测试可能无法编译；此脚本只编译本功能的定向测试。
$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$moduleRoot = Join-Path $backendRoot 'smartorder-agent'
$temporaryRoot = Join-Path $backendRoot 'tmp'
New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
$classpathFile = Join-Path $temporaryRoot 'agent-resume-test-classpath.txt'
$argumentsFile = Join-Path $temporaryRoot 'agent-resume-test.args'
try {
    Push-Location $backendRoot
    & mvn.cmd -o -pl smartorder-agent dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw '无法生成测试类路径' }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim().Replace('\', '/')
    $sources = @(
        'smartorder-agent/src/test/java/com/smartorder/edu/agent/stream/AgentStreamCursorTest.java',
        'smartorder-agent/src/test/java/com/smartorder/edu/agent/stream/AgentChatTaskServiceTest.java',
        'smartorder-agent/src/test/java/com/smartorder/edu/agent/stream/AgentEventLogTest.java',
        'smartorder-agent/src/test/java/com/smartorder/edu/agent/stream/AgentTaskControllerDisconnectTest.java'
    )
    $arguments = @('--release', '21', '-encoding', 'UTF-8', '-cp',
        ('"smartorder-common/target/classes;smartorder-agent/target/classes;' + $classpath + '"'),
        '-d', 'smartorder-agent/target/test-classes') + $sources
    $arguments | Set-Content -LiteralPath $argumentsFile -Encoding utf8
    & javac "@$argumentsFile"
    if ($LASTEXITCODE -ne 0) { throw '定向测试编译失败' }
    & mvn.cmd -o -pl smartorder-agent '-Dtest=AgentStreamCursorTest,AgentChatTaskServiceTest,AgentEventLogTest,AgentTaskControllerDisconnectTest' surefire:test
    if ($LASTEXITCODE -ne 0) { throw '定向测试执行失败' }
} finally {
    Pop-Location
    # 只清理脚本自己产生的两个文件，保留用户工作区内容。
    foreach ($target in @($classpathFile, $argumentsFile)) {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
    }
}
