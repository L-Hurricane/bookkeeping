Component({
  properties: {
    open: { type: Boolean, value: false },
    aboveTab: { type: Boolean, value: false },
  },
  methods: {
    onToggle() {
      this.triggerEvent("toggle");
    },
    onExpense() {
      this.triggerEvent("expense");
    },
    onIncome() {
      this.triggerEvent("income");
    },
    onTransfer() {
      this.triggerEvent("transfer");
    },
  },
});
