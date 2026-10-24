export interface TransferTimes {
  readonly fromBusinessTime: string;
  readonly toBusinessTime: string;
  readonly synced: boolean;
}

export function initialTransferTimes(fromBusinessTime: string): TransferTimes {
  return { fromBusinessTime, toBusinessTime: fromBusinessTime, synced: true };
}

export function changeFromBusinessTime(times: TransferTimes, fromBusinessTime: string): TransferTimes {
  return {
    fromBusinessTime,
    toBusinessTime: times.synced ? fromBusinessTime : times.toBusinessTime,
    synced: times.synced,
  };
}

export function changeToBusinessTime(times: TransferTimes, toBusinessTime: string): TransferTimes {
  return {
    fromBusinessTime: times.synced ? toBusinessTime : times.fromBusinessTime,
    toBusinessTime,
    synced: times.synced,
  };
}

export function unsyncTransferTimes(times: TransferTimes): TransferTimes {
  return { ...times, synced: false };
}

export function resyncTransferTimes(times: TransferTimes): TransferTimes {
  return {
    fromBusinessTime: times.fromBusinessTime,
    toBusinessTime: times.fromBusinessTime,
    synced: true,
  };
}
