import { Notice } from "./Notice";

/** Shown wherever money is discussed until the shop publishes a full refund policy. */
export function RefundNotice() {
  return (
    <Notice tone="warn">
      <p className="m-0 text-[15px]">
        <strong>We do not have a full refund policy yet.</strong> Please think carefully before you
        pay. Your legal rights under the consumer law where you live are not affected.
      </p>
    </Notice>
  );
}
