import type { TransferView } from "./contract";

export interface TransferPostSavePayload {
  readonly sourceAccountId: number;
  readonly transferId: number;
  readonly fromAccountId: number;
  readonly toAccountId: number;
  readonly fromBusinessTime: string;
  readonly toBusinessTime: string;
}

export function transferPostSavePayload(sourceAccountId: number, transfer: TransferView): TransferPostSavePayload {
  return {
    sourceAccountId,
    transferId: transfer.id,
    fromAccountId: transfer.fromAccountId,
    toAccountId: transfer.toAccountId,
    fromBusinessTime: transfer.fromBusinessTime,
    toBusinessTime: transfer.toBusinessTime,
  };
}
