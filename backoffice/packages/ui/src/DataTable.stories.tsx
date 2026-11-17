import type { Meta, StoryObj } from "@storybook/react-vite";
import { Badge, DataTable } from "./index";

type Row = { id: string; bankPair: string; reservedMinor: number; status: string };

const meta: Meta<typeof DataTable<Row>> = { title: "Data/DataTable", component: DataTable<Row> };
export default meta;

export const Settlements: StoryObj<typeof DataTable<Row>> = {
  args: {
    rows: [
      { id: "1", bankPair: "BANKA→BANKB", reservedMinor: 1250000, status: "RESERVED" },
      { id: "2", bankPair: "BANKA→BANKC", reservedMinor: 98000, status: "RELEASED" },
    ],
    rowKey: (r) => r.id,
    columns: [
      { key: "bankPair", label: "Bank pair" },
      { key: "reservedMinor", label: "Reserved", align: "right" },
      { key: "status", label: "Status", render: (r) => <Badge tone={r.status === "RESERVED" ? "warn" : "ok"}>{r.status}</Badge> },
    ],
  },
};
