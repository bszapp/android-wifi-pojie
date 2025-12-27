# wifi密码工具
一款通过暴力破解密码本的方式连接wifi的工具

~~视频演示地址： [自制安卓免Root wifi密码暴力破解工具v2.1](https://www.bilibili.com/video/BV1EbxjzZExd/)~~（稿件被锁定且被封号）

视频演示地址： [MEGA云盘](https://mega.nz/embed/ShY1GKCB#i5Weok9p_Svrm2wTX92CiOF1O8V4y67e_6gCHtF6vFc)


<div align="center">
  <img src="https://raw.githubusercontent.com/bszapp/android-wifi-pojie/refs/heads/main/1.png" style="width: 49%; display: inline-block; margin: 0 0.5%;">
  <img src="https://raw.githubusercontent.com/bszapp/android-wifi-pojie/refs/heads/main/2.png" style="width: 49%; display: inline-block; margin: 0 0.5%;">
</div>


---

项目近期在使用 kotlin + compose material3 重构，由于作者繁忙，目前不会处理任何反馈，但会参考来完成新版本，近期不会更新（2025/12/7）

暑假之后出新版，预告一下最大的更新是新版本支持同时尝试多个，界面大更新，最快速度是上一版本最快速度的两倍（最快一秒尝试三个）（2025/12/28）

<img src="https://github.com/user-attachments/assets/8791782d-b26c-4cc0-8e48-8d8154a6129b" style="width: 49%; display: inline-block; margin: 0 0.5%;">

---

### 免责声明
破解他人wifi密码属于违法行为，本工具仅供网络安全测试使用，请对自己的wifi尝试，**勿用于非法用途，因此造成的后果与作者无关，继续使用代表同意此条款**

## 使用说明

### 操作流程
1. 选择wifi（选择/手动输入名称）
2. 选择密码本（建议几千行的级别，太长时间过长还可能会崩溃）
3. 调整参数（根据wifi信号强度调整超时时间，建议先测量密码错误需要的时间然后取一半）
4. 开始运行

### 注意事项
- 好的配置参数可以取得更好的效果
- 请在运行前检查系统网络设置关闭所有自动连接wifi

### 后台运行
如果需要再后台运行任务，推荐读取状态方式选择命令行，系统API模式请让应用处于画中画或者小窗模式，否则会导致收不到状态变化事件。
读取方式为命令行时请给应用电池优化设置为无限制，并且对于各系统的手机需要进行额外操作（如小米需要锁定任务、添加自启动权限）

### Q&A
#### 为什么明明密码对了还是显示timeout？
可能是后台运行无法读取到状态或者命令行格式不支持，请尝试切换读取网络连接状态工作模式

#### 为什么每个密码要尝试好几秒，能不能快点？
连接失败标志最推荐使用“握手超次”模式，最快可以缩短时间到0.5秒以内，条件不支持可以使用“握手超时”模式，同样可以缩短到1秒左右

#### 单线程太慢了，为什么不能多线程运行？
Android系统没有提供同时连接多个wifi的功能，并且硬件不支持这样的行为

#### 为什么不能用aircrack-ng来抓握手包本地跑字典？
本项目类似幻影wifi控制系统连接指定网络，支持监听模式的手机比较少见，如果需要这种操作推荐单独购买硬件或者使用电脑

## 工作原理
```
忘记此网络
开始监听wifi连接事件
遍历wifi密码本：
|	使用ssid和密码连接wifi
|	等待监听结果
|	失败/超时：
|	|	忘记此网络
|	|	更新进度显示
|	成功：
|	|	停止监听wifi
|	|	结束运行
全部遍历完成：
|	停止监听wifi
|	结束运行
```
