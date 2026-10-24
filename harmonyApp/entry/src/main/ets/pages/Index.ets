import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { CachedSchedule, ClassPeriod, Lesson, Notice, ScheduleSnapshot, SyncResult,
  currentWeek, dayFor, schoolUrlAllowed, validSnapshot } from '../model/Schedule';
import { ScheduleCache } from '../services/ScheduleCache';

const SCHOOL: string = 'https://jwxt.ahtcm.edu.cn';
const CAS: string = 'https://tpass.ahtcm.edu.cn';
const WELCOME: string = SCHOOL + '/new/welcome.page?ui=new';

@Entry
@Component
struct Index {
  @State private snapshot: ScheduleSnapshot | undefined = undefined;
  @State private notices: Notice[] = [];
  @State private week: number = 1;
  @State private page: number = 0;
  @State private showLogin: boolean = false;
  @State private browserMounted: boolean = true;
  @State private busy: boolean = false;
  @State private status: string = '请连接教务，或查看上次成功同步的离线课表';
  @State private detail: Lesson | undefined = undefined;
  private controller: webview.WebviewController = new webview.WebviewController();
  private parser: webview.WebviewController = new webview.WebviewController();
  private ports: webview.WebMessagePort[] = [];
  private parserReady: boolean = false;
  private account: string = '';
  private accountKey: string = '';
  private epoch: number = 0;
  private captureToken: string = '';
  private weekdays: string[] = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  private sections: number[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];
  private days: number[] = [0, 1, 2, 3, 4, 5, 6];

  private cache(): ScheduleCache {
    return new ScheduleCache((getContext(this) as common.UIAbilityContext).filesDir);
  }

  aboutToAppear(): void {
    const saved = this.cache().load();
    if (saved) {
      this.snapshot = saved.snapshot;
      this.notices = saved.notices;
      this.accountKey = saved.accountKey;
      this.week = currentWeek(saved.snapshot);
      this.status = '已载入离线课表；连接教务后才能确认最新安排';
    }
  }

  aboutToDisappear(): void { this.epoch++; this.closePorts(); }

  private closePorts(): void {
    this.captureToken = '';
    this.ports.forEach((port: webview.WebMessagePort) => { try { port.close(); } catch (_) {} });
    this.ports = [];
  }

  private async raw(name: string): Promise<string> {
    const bytes = await (getContext(this) as common.UIAbilityContext).resourceManager.getRawFileContent(name);
    return new util.TextDecoder('utf-8').decodeWithStream(bytes);
  }

  private async captureAccount(): Promise<void> {
    if (!this.controller.getUrl().startsWith(CAS + '/')) return;
    this.closePorts();
    const token = util.generateRandomUUID();
    this.captureToken = token;
    const ports = this.controller.createWebMessagePorts();
    this.ports = ports;
    ports[1].onMessageEvent((result: webview.WebMessage) => {
      if (this.captureToken === token && typeof result === 'string' && /^[0-9]{8,20}$/.test(result)) this.account = result;
    });
    const script = await this.raw('capture-account.js');
    if (this.captureToken !== token || !this.controller.getUrl().startsWith(CAS + '/')) return;
    await this.controller.runJavaScript(script + '\ninstallAccountCapture(' + JSON.stringify(token) + ');true');
    if (this.captureToken === token && this.controller.getUrl().startsWith(CAS + '/')) {
      this.controller.postMessage(token, [ports[0]], CAS);
    }
  }

  private connect(): void {
    if (this.busy) return;
    this.epoch++;
    this.closePorts();
    this.account = '';
    this.browserMounted = false;
    this.showLogin = true;
    this.status = '请在学校网页完成登录，再点击“完成登录并同步”';
    // Recreate an isolated incognito WebView so an old account cannot bind a new login.
    setTimeout(() => {
      this.controller = new webview.WebviewController();
      this.browserMounted = true;
    }, 30);
  }

  private unpackResult(raw: string): Object {
    let value = JSON.parse(raw) as Object;
    if (typeof value === 'string') value = JSON.parse(value) as Object;
    return value;
  }

  private async synchronize(): Promise<void> {
    if (this.busy) return;
    if (!this.parserReady || !/^[0-9]{8,20}$/.test(this.account) || !this.controller.getUrl().startsWith(SCHOOL + '/')) {
      this.showLogin = true;
      this.status = '请在学校登录页提交账号并完成登录';
      return;
    }
    this.busy = true;
    const epoch = ++this.epoch;
    const account = this.account;
    const job = util.generateRandomUUID();
    this.status = '正在读取官方完整课表与作息时间';
    try {
      const script = await this.raw('school-sync.js');
      if (epoch !== this.epoch) return;
      await this.controller.runJavaScript('window.__ahuJob=' + JSON.stringify(job) + ';\n' + script + '\ntrue');
      let result: SyncResult | undefined = undefined;
      // Poll a synchronous result: ArkWeb need not await an in-page fetch Promise.
      const deadline = Date.now() + 120000;
      while (Date.now() < deadline && epoch === this.epoch) {
        await new Promise<void>((resolve: () => void) => { setTimeout(resolve, 250); });
        const raw = await this.controller.runJavaScript('JSON.stringify(window.__ahuResult || null)');
        result = this.unpackResult(raw) as SyncResult;
        if (!result || result.job !== job) throw new Error('同步页面已变化');
        if (result.state === 'error') throw new Error('学校同步未完成');
        if (result.state === 'done') break;
      }
      if (epoch !== this.epoch) return;
      if (!result || result.state !== 'done' || !result.payload) throw new Error('同步超时');
      const parse = 'JSON.stringify(AhuSchedule.parsePayload(' + JSON.stringify(result.payload) + ', XLSX))';
      const snapshot = this.unpackResult(await this.parser.runJavaScript(parse)) as ScheduleSnapshot;
      if (!validSnapshot(snapshot)) throw new Error('课表校验失败');
      const md = cryptoFramework.createMd('SHA256');
      await md.update({ data: new util.TextEncoder().encodeInto(account) });
      const digest = await md.digest();
      let key: string = '';
      for (let i = 0; i < digest.data.length; i++) key += digest.data[i].toString(16).padStart(2, '0');
      if (epoch !== this.epoch || account !== this.account) return;
      const notices = result.noticesError ? (key === this.accountKey ? this.notices : []) : (result.notices || []);
      const saved: CachedSchedule = { schema: 1, accountKey: key, snapshot, notices };
      this.cache().save(saved);
      this.accountKey = key;
      this.snapshot = snapshot;
      this.notices = notices;
      this.week = currentWeek(snapshot);
      this.showLogin = false;
      this.page = 0;
      this.status = result.noticesError || '已同步最新课表，共 ' + snapshot.lessons.length + ' 条课程安排';
    } catch (_) {
      this.status = '同步未完成，原课表已保留。请检查网络、学校登录和教务访问权限';
    } finally { if (epoch === this.epoch) this.busy = false; }
  }

  private cell(day: number, section: number): Lesson[] {
    if (!this.snapshot) return [];
    const date = dayFor(this.snapshot, this.week, day);
    return this.snapshot.lessons.filter((lesson: Lesson) => lesson.date === date && lesson.sections.includes(section));
  }

  private period(section: number): string {
    const periods: ClassPeriod[] = this.snapshot ? this.snapshot.periods : [];
    const period = periods.find((value: ClassPeriod) => value.section === section);
    return period ? period.start + '\n' + period.end : '待同步';
  }

  @Builder
  private timetable() {
    Column({ space: 8 }) {
      if (this.snapshot) {
        Text(this.snapshot.termLabel).fontSize(15).fontColor('#385B53')
        Row() {
          Button('上一周').onClick(() => { this.week = Math.max(1, this.week - 1); })
          Text('第 ' + this.week + ' 周').layoutWeight(1).textAlign(TextAlign.Center)
          Button('下一周').onClick(() => { this.week = Math.min(30, this.week + 1); })
        }.width('100%')
        Scroll() {
          Column() {
            Row() {
              Text('节次').width(46).fontSize(11)
              ForEach(this.days, (day: number) => {
                Text(this.weekdays[day] + '\n' + (this.snapshot ? dayFor(this.snapshot, this.week, day).slice(5) : ''))
                  .fontSize(11).layoutWeight(1).textAlign(TextAlign.Center)
              })
            }.height(44)
            ForEach(this.sections, (section: number) => {
              Row({ space: 2 }) {
                Text(section + '\n' + this.period(section)).width(44).fontSize(10).fontColor('#4A625C')
                ForEach(this.days, (day: number) => {
                  Column({ space: 2 }) {
                    ForEach(this.cell(day, section), (lesson: Lesson) => {
                      Text(lesson.name + '\n' + lesson.location).fontSize(10).maxLines(4)
                        .fontColor('#163F36').width('100%').padding(3).borderRadius(5)
                        .backgroundColor('#D6ECE5').onClick(() => { this.detail = lesson; })
                    })
                  }.layoutWeight(1).height(80).backgroundColor('#F3F8F6')
                })
              }.height(82).width('100%')
            })
          }.width('100%')
        }.layoutWeight(1).width('100%')
        Text('上次成功获取：' + this.snapshot.retrievedAt.replace('T', ' ').slice(0, 16) + ' UTC')
          .fontSize(11).fontColor('#5C6E68')
      } else {
        Text('还没有课表').fontSize(22).margin({ top: 56 })
        Text('连接学校教务系统后，课程会保存在本机供离线查看。').fontSize(14).margin(20)
        Button('连接教务').onClick(() => { this.connect(); })
      }
    }.layoutWeight(1).width('100%')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 12 }) {
        Row() {
          Text('岐黄课表').fontSize(25).fontWeight(FontWeight.Bold).layoutWeight(1)
          Button(this.busy ? '同步中' : '同步').enabled(!this.busy).onClick(() => { this.synchronize(); })
        }.width('100%')
        Text(this.status).fontSize(12).fontColor('#4A625C').width('100%')
        Row({ space: 8 }) {
          Button('我的课表').onClick(() => { this.page = 0; })
          Button('教务通知').onClick(() => { this.page = 1; })
          Button('重新登录').enabled(!this.busy).onClick(() => { this.connect(); })
        }
        if (this.page === 0) {
          this.timetable()
        } else {
          Scroll() {
            Column({ space: 10 }) {
              if (this.notices.length === 0) { Text('暂无已同步通知').fontSize(15) }
              ForEach(this.notices, (notice: Notice) => {
                Column({ space: 6 }) {
                  Text(notice.text).fontSize(14).width('100%')
                  Text(notice.publishedAt).fontSize(11).fontColor('#63786F').width('100%')
                }.padding(14).borderRadius(12).backgroundColor('#EDF5F0').width('100%')
              }, (notice: Notice) => notice.id)
            }.width('100%')
          }.layoutWeight(1)
        }
        Text('HarmonyOS 适配预览版').fontSize(10).fontColor('#72877E')
      }.width('100%').height('100%').padding(16).backgroundColor('#FAFDFB')

      // Isolated offline parser: no native bridge and no network access in its CSP.
      Web({ src: $rawfile('parser.html'), controller: this.parser })
        .width(1).height(1).opacity(0).javaScriptAccess(true)
        .onPageEnd(() => { this.parserReady = true; })

      if (this.browserMounted) {
        Column() {
          if (this.showLogin) {
            Row({ space: 8 }) {
              Button('返回课表').enabled(!this.busy).onClick(() => { this.showLogin = false; })
              Button(this.busy ? '正在同步' : '完成登录并同步').enabled(!this.busy).onClick(() => { this.synchronize(); })
            }.padding(8)
            Text(this.status).fontSize(12).padding(8)
          }
          Web({ src: WELCOME, controller: this.controller, incognitoMode: true })
            .javaScriptAccess(true).domStorageAccess(true).fileAccess(false)
            .mixedMode(MixedMode.None).multiWindowAccess(false)
            .width('100%').layoutWeight(1)
            .onLoadIntercept((event) => event ? event.data.isMainFrame() && !schoolUrlAllowed(event.data.getRequestUrl()) : true)
            .onPageBegin((event) => {
              if (event && !schoolUrlAllowed(event.url)) { this.controller.stop(); this.status = '已阻止非学校页面'; }
            })
            .onPageEnd((event) => {
              if (event && event.url.startsWith(CAS + '/')) this.captureAccount().catch(() => { this.status = '账号识别未就绪，请重新登录'; });
            })
            .onSslErrorEventReceive((event) => { if (event) event.handler.handleCancel(); })
        }.width(this.showLogin ? '100%' : 1).height(this.showLogin ? '100%' : 1)
          .opacity(this.showLogin ? 1 : 0).backgroundColor('#FAFDFB')
      }

      if (this.detail) {
        Column({ space: 10 }) {
          Text(this.detail.name).fontSize(22).fontWeight(FontWeight.Bold)
          Text(this.detail.date + ' · 第' + this.detail.week + '周 · ' + this.detail.sections.join(',') + '节')
          Text(this.detail.kind + '\n' + this.detail.teachers + '\n' + this.detail.location)
          Scroll() { Text(this.detail.content).fontSize(15).width('100%') }.constraintSize({ maxHeight: 280 })
          Button('关闭详情').onClick(() => { this.detail = undefined; })
        }.padding(22).width('90%').borderRadius(18).backgroundColor('#EAF3EE').margin({ top: 80, left: '5%' })
      }
    }.width('100%').height('100%')
  }
}
