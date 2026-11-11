---
name: childhood-arcade-tv
description: 把童年游戏厅安装或更新到Android电视，并检查连接、备份存档和验证启动。用户说“安装游戏厅”“更新电视游戏厅”“继续安装游戏厅”或要求检查、备份本项目电视应用时使用。
---

# 童年游戏厅一句话部署

用户直接发安装/更新请求时，运行部署流程；这已授权本次安装，无需再问一次“是否安装”。skill负责电脑上的操作，APK负责电视独立运行。首次仍需用户在电视开启ADB并允许此电脑调试；skill不能代替电视端授权，也不能让互不连通的网络自动互通。

本目录入口为 `scripts/tv.py`，只需Python3和官方ADB。配置、已验证电视标识、备份与执行报告默认保存在 `~/.config/childhood-arcade`；可用 `--home` 或 `ARCADE_DEPLOY_HOME` 隔离测试。查看支持的命令用 `python3 scripts/tv.py --help`。

## 执行

1. 对尚未配置用户，使用用户给出的真实 GitHub OWNER/REPO，运行 `python3 scripts/setup_release.py --repo OWNER/REPO --serial 当前电视IP`；无 IP 可省略 serial 仅准备文件。固定版本/文件名/哈希与 provider 传输协议在 assets/release.json。不可猜仓库、换内核或混用旧调试包。Windows 使用实际可用的 Python 命令。
2. 先运行 `python3 scripts/tv.py status`。本机已配置APK时直接使用；缺少配置时按 [references/operations.md](references/operations.md) 的首次配置处理。发布素材和自动下载只可使用已核实许可且经审核的版本；不可上传授权未确认的资源。
3. 用户提供了当前电视IP，运行 `python3 scripts/tv.py deploy --serial IP`；已经绑定过这台电视，运行 `python3 scripts/tv.py deploy`。默认传统ADB端口5555，也接受明确的IP:端口或USB设备serial。不扫描网络，不自动选择ADB列表里的第一台设备，不使用历史截图IP作为当前目标。更换地址后，脚本仍会核对设备身份。
4. 脚本校验APK哈希，连接并检查SDK/空间/设备身份，更新前备份已保存的进度，覆盖安装，启动，通过受限部署接口读取实际页面状态并截图。报告从stdout输出JSON，stderr有阶段进度。安装使用 `-r`，不卸载、不清数据、不修改系统应用或桌面。若电视现有APK的完整SHA-256与目标相同，先备份，再跳过重复安装；仍检查资源与启动页面。配置含 `game_pack_manifest` 时，逐项核对本地资源和电视文件，断电后重跑会跳过已验证文件，保留不一致的已有文件并报告；资源未齐不算部署完成。
5. 按返回状态继续：
   - `installed_verified`：安装与页面显示已验证；报告截图、备份位置和未验证项目。不要声称实体手柄、人工听音或目标电视全部游戏都已通过。
   - `needs_target`：只补问电视当前IP以及能否从这台电脑到达；调试入口和VPN情况见参考文件。
   - `needs_authorization`：请用户在电视允许调试；用户回复后直接再次运行部署，脚本会使用待授权地址，不再要求重填。
   - `network_unreachable` / `connection_timeout` / `device_offline`：报告具体连接失败，指导核对IP、电视调试开关、局域网/VPN访问；普通VPN不等于两地设备可以互连。
   - `target_changed` / `wrong_model`：停止安装，核对用户是否主动更换电视；不能自行解除绑定。
   - `signature_conflict` / `backup_unavailable` / `insufficient_space` / `apk_changed`：按报告排查，保留旧数据；不通过卸载、清除数据或下载未知APK来重试。
   - `installed_unverified`：APK已经安装，页面自动检查未完成。查看截图或让用户确认画面；不误报完成，也不反复安装。

只有需要用户操作或缺少当前目标时提问。已给出的安装授权不因切换对话步骤而失效。每次运行检查真实返回结果，不把预期输出、模拟器记录或“进程存在”当作当前电视成功证据。

## 检查和备份

`python3 scripts/tv.py doctor` 检查已配置电视；`python3 scripts/tv.py backup --output NEW_FILE.json` 备份设置、收藏和已保存进度，不含ROM。provider 模式通过正式接口导出 ZIP，包含已保存状态和 NES SRAM，不包含 ROM 或未保存进度。可使用 `restore --input PATH.zip` 恢复，会停止游戏厅并覆盖同名已保存状态；先核对备份和目标。遇到旧调试版签名冲突停止，不卸载。

测试或故障处理细节、首次配置、安装触发示例与验证边界见 [references/operations.md](references/operations.md)。
