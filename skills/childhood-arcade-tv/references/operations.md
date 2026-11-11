# 正式版操作

此 Skill 使用 Python 3.9+ 和官方 ADB。入口 scripts/tv.py；固定发行版本和文件哈希在 assets/release.json。正式版本关闭 APK 和 WebView 调试，以 provider 协议部署；不能使用旧 legacy 配置冒充正式部署。

首次调用 `python3 scripts/setup_release.py --repo OWNER/REPO --serial 用户提供的IP`。GitHub Release 尚未创建、网络失败或哈希不符时，报告真实失败。用户可提供本地文件；运行 configure 时必须带 `--transport provider`、已核实 APK 哈希和资源 manifest 路径。首次仍需在电视开启 ADB 并允许电脑。

status 只看本地配置；doctor --serial IP 为只读检查。deploy --serial IP 绑定明确目标并安装，更新前备份，不卸载、不清数据；wrong_model、target_changed、signature_conflict、backup_unavailable 时停止并保留数据。0.7.4 调试签名与 1.0.0 发行签名不同，不能直接覆盖。

配置、备份与报告默认位于 ~/.config/childhood-arcade，--home 或 ARCADE_DEPLOY_HOME 可隔离。配置和备份有个人路径和设备信息，不上传仓库。

provider 备份会停止游戏厅，生成包含已保存设置、即时状态、电池存档和 NES SRAM 的 ZIP；不含 ROM 和未保存进度。restore --input PATH.zip 会在核对设备后停止应用并恢复，覆盖同名已保存数据；拒绝未知路径和损坏 ZIP。恢复失败回滚，应用重新启动时恢复中断事务。恢复完成后重新打开游戏厅再人工验证。

页面确认通过新鲜的应用内健康状态，而非进程存在；installed_unverified 不能宣称页面成功，更不能宣称实体手柄和游戏流畅。声音、操作和保存后的重开均需要实机验证。

电脑和电视需网络互通，VPN 不必然提供两地互联。不得打开公网 ADB；不要自动更改路由器、VPN 或系统应用。用户授权安装请求本身已允许执行所需安装，无需重复询问。
