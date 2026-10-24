import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { CachedSchedule, validSnapshot } from '../model/Schedule';

export class ScheduleCache {
  private directory: string;
  constructor(directory: string) { this.directory = directory; }

  private keyAllowed(key: string): boolean { return /^[a-f0-9]{64}$/.test(key); }

  private replace(name: string, content: string): void {
    const target = this.directory + '/' + name;
    const temporary = target + '.tmp';
    const file = fileIo.openSync(temporary, fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
    try {
      const expected = new util.TextEncoder().encodeInto(content).byteLength;
      if (fileIo.writeSync(file.fd, content) !== expected) throw new Error('课表缓存未完整写入');
      fileIo.fsyncSync(file.fd);
    } finally { fileIo.closeSync(file); }
    fileIo.renameSync(temporary, target);
  }

  load(): CachedSchedule | undefined {
    try {
      const key = fileIo.readTextSync(this.directory + '/active-account').trim();
      if (!this.keyAllowed(key)) return undefined;
      const path = this.directory + '/schedule-' + key + '.json';
      if (fileIo.statSync(path).size > 2 * 1024 * 1024) return undefined;
      const saved = JSON.parse(fileIo.readTextSync(path)) as CachedSchedule;
      if (saved.schema !== 1 || saved.accountKey !== key || !validSnapshot(saved.snapshot) || !Array.isArray(saved.notices)) return undefined;
      return saved;
    } catch (_) { return undefined; }
  }

  save(saved: CachedSchedule): void {
    if (!this.keyAllowed(saved.accountKey) || !validSnapshot(saved.snapshot)) throw new Error('无效课表');
    const content = JSON.stringify(saved);
    if (new util.TextEncoder().encodeInto(content).byteLength > 2 * 1024 * 1024) throw new Error('课表缓存过大');
    // Persist the fully validated account snapshot before changing the active pointer.
    this.replace('schedule-' + saved.accountKey + '.json', content);
    this.replace('active-account', saved.accountKey);
  }
}
