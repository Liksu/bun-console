// Mortgage calculator: change a number, press Run File.
const principal = 300_000; // loan
const yearlyRate = 0.045; // 4.5 % a year
const years = 25;

const money = (value: number) =>
  value.toLocaleString("en-US", { style: "currency", currency: "USD" });

function monthlyPayment(rate = yearlyRate, term = years): number {
  const r = rate / 12;
  return (principal * r) / (1 - (1 + r) ** -(term * 12));
}

function schedule(rate = yearlyRate) {
  const payment = monthlyPayment(rate);
  const rows = [];
  let balance = principal;
  let interest = 0;
  for (let month = 1; month <= years * 12; month++) {
    const part = (balance * rate) / 12;
    interest += part;
    balance = Math.max(0, balance - (payment - part));
    if (month % 60 === 0) {
      const year = month / 12;
      rows.push({ year, interest: money(interest), balance: money(balance) });
    }
  }
  return rows;
}

const payment = monthlyPayment();
console.log("Monthly payment:", money(payment));
console.log("Total interest: ", money(payment * years * 12 - principal));
console.table(schedule());

// Select the line below and press Run Selection:
[3, 4, 5].map((p) => `${p}%: ${money(monthlyPayment(p / 100))}`);
