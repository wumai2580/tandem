# Tandem 一键提交推送
# 用法:  .\scripts\push.ps1 "更新说明"
# Token: 存到仓库根目录 .gh-token（已 gitignore），或设置环境变量 $env:GH_TOKEN
param([string]$Message = "update")

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$token = $env:GH_TOKEN
if (-not $token -and (Test-Path ".gh-token")) { $token = (Get-Content ".gh-token" -Raw).Trim() }
if (-not $token) { throw "没有找到 token：把 GitHub token 写进 .gh-token 或设置 GH_TOKEN" }

$remote = (git remote get-url origin) -replace "^https://", "https://x-access-token:$token@"

git add -A
if (git diff --cached --quiet) { Write-Host "没有改动，跳过 commit"; exit 0 }

git -c user.name="wumai2580" -c user.email="wumai2580@users.noreply.github.com" commit -m $Message
git -c http.proxy=http://127.0.0.1:7897 push $remote HEAD:main
Write-Host "✅ 已推送到 $(git remote get-url origin)"
