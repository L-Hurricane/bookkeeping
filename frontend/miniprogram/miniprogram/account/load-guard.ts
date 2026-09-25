export interface LoadGuard {
  generation: number;
  pageAlive: boolean;
  loggingOut: boolean;
}

export function createLoadGuard(): LoadGuard {
  return { generation: 0, pageAlive: true, loggingOut: false };
}

export function beginLoad(guard: LoadGuard): number | null {
  if (!guard.pageAlive || guard.loggingOut) {
    return null;
  }
  guard.generation += 1;
  return guard.generation;
}

export function beginLogout(guard: LoadGuard): boolean {
  if (!guard.pageAlive || guard.loggingOut) {
    return false;
  }
  guard.loggingOut = true;
  guard.generation += 1;
  return true;
}

export function endLogout(guard: LoadGuard): void {
  guard.loggingOut = false;
}

export function canApplyLoadResult(guard: LoadGuard, generation: number): boolean {
  return guard.pageAlive && !guard.loggingOut && guard.generation === generation;
}
