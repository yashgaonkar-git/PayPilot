# PayPilot business requirements

PayPilot centralizes bills, due dates and payment schedules so users can organize their finances and avoid missed payments and late fees.

## Review of the supplied Markdown

The draft accurately summarizes the problem, key features, stakeholder list, platform and privacy constraints, and the main navigation branches on PDF pages 1–7. It is incomplete as a development specification. The PDF is a 44-page image-only deck. The page references below use PDF page numbers.

| PDF pages | Corrections and additions |
| --- | --- |
| 5–7 | Retain the stakeholder roles and main flows. Read “PayPal Login page” in the flowchart as PayPilot, consistent with the title and UI. |
| 8–10 | Restore bill editing and deletion, a due-date calendar or timeline, gateway integration, traceable transactions and adjustments, backup requirements, and measurable capacity and response targets. |
| 11–18 | Restore MoSCoW definitions, account request fields and admin approval, image challenge, email OTP, failed-attempt lockout, and password recovery or change. |
| 19–30 | Restore bill filters and category counts, form fields, recurring instances, partial balances, Pay Now, PDF attachments, snooze dates, and per-bill reminder settings. |
| 31–40 | Restore payment form fields, confirmation, edit and cancel flows, progress colors, and date-filtered overview and history with payment modes. |
| 40–43 | The actual architecture drawing names Java 1.8/Spring, Hibernate ORM and Oracle 11g. The draft’s suggested stack, OAuth/JWT, FCM, Stripe/Plaid and proposed database tables are additions. The ER diagram separates completed payments, scheduled payments and per-bill reminder settings. |

## Stakeholders and priority method

The product owner manages vision, feature scope and priorities. Developers implement and maintain the app. Customer support handles questions, troubleshooting and feedback. Users manage bills and payments. The application administrator maintains backend and signup data and approves access. Use-case tables also name financial advisors as actors without defining a separate advisor permissions model.

Page 11 describes MoSCoW. Must Have is essential to delivery, Should Have is important with a possible workaround, Could Have is useful with lower impact, and Won’t Have is deferred for this version. The PDF does not assign a MoSCoW rank to every story. Its security and maintenance tables explicitly identify Highest and High priorities as shown below.

## Functional requirements

### FR-01 Account request, sign-in and recovery (pp. 7–8, 12–18)

- Request access from sign-in. Signup asks for user ID, email, PAN, bank account number, IFSC, banking partner, password and confirmation. Validate input and duplicate records. The use case also mentions optional duplicate checks on name or contact number without defining those fields in its normal course. Confirm submission, return to sign-in, and require admin approval before access.
- An approved user enters credentials, completes screen verification by selecting images that match a question, verifies an OTP sent to the registered email and signs in to the dashboard. Display feedback for invalid credentials, unregistered account, incorrect OTP and unsuccessful screen verification. Automatically lock the user after three invalid credential attempts.
- Forgot Password requests registered email and user ID, verifies a generated email OTP, and accepts a new password with confirmation. Show success and return to sign-in. The login wireframe also exposes Change Password. Page 4 calls for a secure access option such as PIN or biometrics.
- Dashboard navigation covers Manage Bills, Schedule Payments, and Track Payments. The wireframes show notifications, profile, support and the PayPilot brand as a link to the home screen.

### FR-02 Manage bills (pp. 3–4, 7–8, 19–27)

- Add, view, edit and delete bills. Capture bill name, billing company, category, due date, amount, payment frequency, reminder frequency, optional PDF-only attachment, optional notes and a recurring switch. Replicate recurring bills for the applicable upcoming months and confirm a successful save.
- Provide overview counts by category, category drill-down, bill-name search, bill date from/to and status filters (including Upcoming, Pending and Paid), and a calendar or timeline for upcoming and past-due bills. The overview lists debt payments, house rent, groceries, internet charges, retirement charges and cell phone charges. Pages 4 and 8 also support broader categories such as utilities and subscriptions, plus custom categories.
- Display upcoming and overdue bills with original amount, outstanding amount, bill name, due date, days overdue and payment status. Distinguish unpaid, partial and fully paid. Filter by date range/category and sort by days overdue.
- Let users select bills and use Pay Now for the balance through an available payment method and institution. Offer secure processing through a payment gateway or financial platform with appropriate channels, such as online banking, bank transfer/direct debit, cards and digital wallets.

### FR-03 Snoozing, marking as paid and reminders (pp. 3–4, 7–8, 27–30)

- Search or filter bills by category, select bills, snooze to a chosen date, or manually mark paid. Confirm the operation and show the updated status or reminder state.
- Let users add or change per-bill reminders before due date with daily, weekly, bi-weekly or monthly frequency, start date, customizable message, Mail/Contact delivery preference and recurring-bill setting. The wireframe also shows a recurring on/off control. Confirm successful updates.

### FR-04 Scheduling and changes (pp. 3, 7, 31–37)

- From Schedule Payments, filter bills by category and date range, select a bill and fill the payment purpose, payee search, bill date, method, payer account, scheduled date and amount. Populate payee name, address and bank details from the selected payee. Allow payment frequency only for recurring bills.
- Show the outstanding and scheduled totals. Require explicit confirmation and show the outcome. The scheduling use case describes future payment execution without manually initiating each transfer.
- Filter and view scheduled payments by category and date range. Select a schedule and cancel it or change its scheduled date, then confirm the result.

### FR-05 Tracking and reporting (pp. 4, 7, 37–40)

- Progress allows a category filter or bill-name search. The written use case specifies red for unpaid/pending, green for completely paid, and blue for partially paid. Include textual statuses with the visual indicators.
- Overview filters category and date range, then shows bill details with payment status such as paid or due soon. History uses the same filters and shows bill details, date paid and payment mode. Also show outstanding balances and the upcoming/overdue and priority views named in the business flow.
- Use visual reports for payment patterns, expenses and trends. The PDF leaves report format and priority rules open.

## Nonfunctional, access and maintenance requirements

| Area | PDF requirement |
| --- | --- |
| Coverage (pp. 6, 9) | Compatibility with iOS, Android, PCs, MacBooks and tablets, plus responsive layouts. The browser list names Chrome, Firefox, DuckDuckGo, Safari, Brave, Vivaldi, Torch, Edge and Opera. The source specifies no packaging mechanism. |
| Service (p. 9) | Reliable around-the-clock access. Announce maintenance in advance and maintain continuity for active users. |
| Capacity and persistence (p. 9) | Process more than 2,000 transactions per second, support 50,000 concurrent users and persist user modifications within two seconds. A workload, measurement percentile and test environment still need definition. |
| Highest priority (pp. 9–10) | Restrict access to permitted users, protect product/supplier information, keep backups of all records, and trace every transaction to its actor. |
| High priority (p. 10) | Protect stored account data against unauthorized read or update and track all adjustment transactions. |
| Data and integration (pp. 4, 6, 8) | Protect credentials and sensitive financial data, comply with applicable privacy regulations (the PDF cites GDPR/CCPA as examples), meet secure payment processing requirements, and integrate with providers where required. Scale as users and bills grow. |

The brief assumes active users, accurate bill details, reliable connectivity, multiple available payment channels, customizable preferences and robust security.

## Design reference (pp. 40–43)

The Java architecture drawing shows configuration, controllers and REST controllers, services and entity/model classes, with repository classes via Hibernate ORM over Oracle 11g. The sequence shows sign-in, filtering bills, selecting a bill, fetching details, saving a schedule, confirmation and retrieving schedules. The ER model defines Users, Bills, Payments, ScheduledPayments and ReminderSettings. Bills belong to users and link to payments, schedules and reminder settings. The class diagram depicts authentication and bill management operations, with some duplicated or garbled labels. The normalized entities and operations above follow the use-case prose and ER diagram where details conflict. Money should use integer minor units and passwords should use salted one-way hashes in an implementation.

## Portfolio implementation and remaining acceptance work

The primary portfolio app uses Java 17, Spring Boot and Hibernate ORM behind the mobile-friendly web app. Hibernate persists accounts, bills, reminder settings, schedules, payment records, receipts and audit events. H2 is the local database. The optional Oracle profile targets Oracle 19c or newer. The PDF diagram's Java 1.8 and Oracle 11g versions differ from the portfolio stack. The earlier Python and SQLite app and its data remain available separately.

| PDF requirement | Portfolio implementation |
| --- | --- |
| Access requests, verification and recovery (pp. 7–18) | Administrator approval, a selectable shape challenge, email OTP or console OTP for the demo, credential lockout, reset and optional PIN. |
| Bill and reminder workflows (pp. 19–30) | Filtered bills, PDF attachments, recurrence, payment status, snooze, per-bill reminders and an SMS handset preview. |
| Schedule and track payments (pp. 31–40) | Bill selection by category and date, sample payees and funding choices, reschedule and cancellation, background simulated settlement and receipts, partial balances and reports. |
| Security and maintenance (pp. 8–10) | Hashed credentials, encrypted financial details and attachments, access-scoped operations, administrator audit view, H2 snapshot tooling and a configurable maintenance notice. |
| Java architecture (pp. 40–43) | Spring controllers and services, a Hibernate persistence layer and relational entities. H2 runs locally. Oracle 19c+ requires a server and test environment. |

Live provider approval, transfers, verified payees and funding sources, carrier SMS, Oracle deployment testing, managed key storage, encrypted offsite backups, device and browser certification, and privacy and payment-security review remain deployment acceptance work. The portfolio demonstration does not establish the PDF's 50,000 concurrent users, more than 2,000 transactions per second, or two-second persistence targets under production load.
