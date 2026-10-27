export interface DayGroup<T> {
  readonly key: string;
  readonly label: string;
  readonly count: number;
  readonly rows: readonly T[];
}

const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

export function groupByTimeLabel<T extends { readonly timeLabel: string }>(
  rows: readonly T[],
  yearMonth: string,
): DayGroup<T>[] {
  const groups: Array<{ key: string; label: string; rows: T[] }> = [];
  for (const row of rows) {
    const day = row.timeLabel.slice(0, 5);
    const key = `${yearMonth.slice(0, 4)}-${day}`;
    const current = groups[groups.length - 1];
    if (current !== undefined && current.key === key) {
      current.rows.push(row);
      continue;
    }
    groups.push({ key, label: dayHeading(key), rows: [row] });
  }
  return groups.map((group) => ({ key: group.key, label: group.label, count: group.rows.length, rows: group.rows }));
}

function dayHeading(key: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key);
  if (match === null) return key;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()] ?? "";
  return `${month}月${day}日 ${weekday}`;
}
