// Mortgage calculator. Change a number, then ask the console.
const principal = 300_000; // loan, $
const yearlyRate = 0.045;  // 4.5 % a year
const years = 25;

type Payment = { month: number; interest: number; principal: number; balance: number };

const round = (value: number) => Math.round(value * 100) / 100;

function monthlyPayment(amount = principal, rate = yearlyRate, term = years): number {
  const r = rate / 12;
  return (amount * r) / (1 - (1 + r) ** -(term * 12));
}

function schedule(amount = principal, rate = yearlyRate, term = years): Payment[] {
  const payment = monthlyPayment(amount, rate, term);
  const rows: Payment[] = [];
  let balance = amount;
  for (let month = 1; balance > 0.005; month++) {
    const interest = (balance * rate) / 12;
    const part = Math.min(payment - interest, balance);
    balance -= part;
    rows.push({ month, interest: round(interest), principal: round(part), balance: round(balance) });
  }
  return rows;
}

function totalInterest(rate = yearlyRate, term = years): number {
  return round(monthlyPayment(principal, rate, term) * term * 12 - principal);
}

// Select a line and press Run Selection:
round(monthlyPayment());
[3, 4, 5].map((percent) => ({ percent, monthly: round(monthlyPayment(principal, percent / 100)) }));
