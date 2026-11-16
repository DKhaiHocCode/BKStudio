# BKStudio policy wording research

Not legal advice. Research date 2026-11-16. Items marked [VERIFIED] were read on the cited page this session. Items marked [SNIPPET] came only from a search-result summary. Items marked [GENERAL] are from general knowledge and were NOT fetched; check before relying on them.

## 0. Real business rules (from repo) the wording must keep

Source: `docs/REQUIREMENTS.md` 2.1-2.6, `docs/WORKFLOWS.md` line 168, `docs/DATABASE.md` 6.

- In-stock: pay 100% once. Item reserved 48 h from when the payment link is sent; then released.
- Made-to-order (incl. commission via UNLISTED product): 50% deposit. Queue position set when deposit is confirmed, not when ordered. Start month shown at order time is an estimate; official month confirmed after deposit.
- Deposit link and in-stock link: 48 h from sending. Balance: 30 days from finished-photos notice; reminders at 7 days and 1 day.
- Customer cancels made-to-order after deposit: deposit kept (CANCELLED_DEPOSIT_KEPT). Shop cancels (cannot make it, print fails, stops taking orders): 100% deposit refund. Deposit/in-stock deadline missed: order cancelled, nothing charged. Balance deadline missed: FORFEITED, deposit kept, figure may be resold.
- Shipping paid by customer, amount told before paying. Tracking emailed.
- Payment by manual link (Artistree or Shopee + proxy buyer service, not settled). Site takes no card data. No customer accounts.
- Data: name, email, optional phone, address, order details; IP + timestamp + terms version at checkout. Anonymise name/email/phone/address 2 years after order ends (job not built yet, T-0xx). Processors named in draft: VPS host, Resend, Artistree/Shopee, carrier, Cloudflare/CDN.
- Terms text for `/policy` lives in DB table `terms_document.content_md` (versioned, `V1__init.sql` line 15). No terms text was found in the repo migrations, so section 2.4 below is a proposed `content_md`.
- Gaps in the real rules the current pages do not cover: lost/damaged parcels, customs/GST/duties, faulty or damaged goods on arrival, IP/fan-art status, commission revisions, deposit refund for non-shop-fault failure to start, what "finished" means.

## 1. Comparison table

| Topic | Artistree | Shopee SG | Good Smile (official stores) | Independent commission artists (Artistree-using) | What BKStudio draft says |
|---|---|---|---|---|---|
| Source quality | NOT VERIFIED. No official Artistree terms/help page could be fetched (`artistree.sg` did not resolve; search returned no official page). Only artist pages that mention Artistree. | Official help centre fetched [VERIFIED] | Official pages [VERIFIED]/[SNIPPET] | Several artist ToS pages [SNIPPET] | n/a |
| Deposits | Not found officially. Artists say payment is by Artistree invoice, may include fees to client; some require full payment before work [SNIPPET]. | No deposit concept found for pre-order. | Pre-order is paid under store rules; not detailed. | Common: deposit non-refundable once work starts; full refund before start [SNIPPET]. | 50% non-refundable once customer cancels; refundable 100% if shop cancels. |
| Cancellation | Not found officially. Artist-level. | Buyer can cancel instantly before shipment arranged, but "Custom-made or made-to-order products" and "Pre-orders product" need seller approval; after 1 h of payment seller approval needed; seller has 2 calendar days, system auto-approves if no reply [VERIFIED: help.shopee.sg article 76733]. | "Normal cancellation policy still applies, all sales are final" for pre-orders [SNIPPET]. | Full refund before work starts; none or discretionary after [SNIPPET]. | Customer cancel after deposit = deposit kept. |
| Refunds | Artist-level; Artistree "Need Help?" button route mentioned by one artist [SNIPPET]. | Return/refund via app within window, then via Chat; shipping fee refunded only if all items from that seller returned [SNIPPET]. | EU shop: 14-day return right (EU law); non-refundable for items so described; non-EU shipping fees may be excluded [VERIFIED: goodsmileeurope.com refund policy]. | Full refund if artist cannot deliver [SNIPPET]. | Shop-cancel = 100% deposit back. Nothing on faulty goods. |
| Shipping / customs / lost parcels | Not found. | Not fetched in detail. Shopee logistics rules apply on-platform. | Buyer pays customs/duties per country; shop will not answer duty queries; not liable for failed delivery caused by wrong address [SNIPPET: goodsmile.com notices]. No lost-parcel policy found. EU shop lists damaged/missing parts "not due to our error" as partial/no refund [VERIFIED]. | Rare. | Customer pays shipping. Nothing on customs or loss. |
| IP / fan art licensing | Not found. | Not fetched. | Licensed products (they hold licences). | Artists usually state personal-use only / no resale / artist keeps copyright [SNIPPET]. | Nothing stated. Biggest unaddressed legal item (see section 3). |
| Privacy retention | Not found. | Not fetched. | Not fetched. | Not stated. | 2 years then anonymise contact + address. |

What this implies: nobody I could verify offers a bespoke made-to-order deposit rule on a marketplace; BKStudio's "non-refundable after customer cancels, full refund if we cancel" matches common artist practice and Shopee's "seller approval needed for made-to-order cancellation". The stronger wording is mutual: state the shop-cancel refund prominently (it is the fairness anchor).

## 2. Legal basics (what was and was not verified)

### 2.1 Singapore PDPA
- [SNIPPET, from PDPC guideline search results; PDPC pages themselves returned no body text to my fetcher] Consent generally needed and valid only if purposes notified; consent withdrawable with reasonable notice; purpose limitation; access and correction on request; retention limitation (stop retaining when no longer needed for a business or legal purpose); organisations must designate a DPO and make DPO business contact public; make policies and complaints process available on request.
- [GENERAL, not fetched] Notifiable data breaches go to PDPC and affected individuals; transfer-limitation rule applies to sending data overseas (VPS in SG, Resend and Cloudflare may be overseas, so wording should say "we use service providers, some outside Singapore"); access requests usually answered within 30 days; a fee may be charged for access.
- URLs: https://www.pdpc.gov.sg/overview-of-pdpa/the-legislation/personal-data-protection-act/data-protection-obligations ; https://www.pdpc.gov.sg/-/media/files/pdpc/pdf-files/advisory-guidelines/ag-on-key-concepts/advisory-guidelines-on-key-concepts-in-the-pdpa-17-may-2022.pdf
- Applicability to a sole proprietor: PDPA applies to organisations incl. individuals acting in a business capacity [GENERAL]. Treat as applicable. Name a contact (DPO role) even if it is Khai.

### 2.2 Australia Privacy Act / APPs
- [SNIPPET, OAIC page returned 403 to my fetcher] "Small business" = annual turnover of AUD 3 million or less and generally outside the Privacy Act, with exceptions (e.g. trading in personal information, handling health info). An overseas business is covered only if it "carries on business in Australia" (s 5B(3)); one secondary commentary says a website accessible from Australia or the ability to place orders there does not by itself establish that. OAIC supports removing the small-business exemption; threshold may have changed by now. Check the live OAIC page.
- Practical stance: BKStudio is probably outside the Act by size, but AU customers will expect a privacy notice. Writing to APP-style standards (clear collection notice, access/correction, overseas disclosure statement, destruction when not needed) is cheap and safe. APP list: https://www.oaic.gov.au/privacy/australian-privacy-principles (not fetchable; 403).

### 2.3 Australian Consumer Law (ACL)
- [VERIFIED https://www.accc.gov.au/consumers/consumer-rights-guarantees/consumer-guarantees] Guarantees: acceptable quality, fit for purpose, matches description, matches sample, express warranties, title. They "can't be taken away by anything a business says or does"; "no refunds" signs do not remove them. Overseas businesses selling directly to Australian consumers must follow ACL including guarantees, though enforcement is harder. Page does not discuss custom-made goods.
- [VERIFIED https://www.accc.gov.au/business/problem-with-a-product-or-service-you-sold/repair-replace-refund-cancel] Major failure: consumer chooses refund or replacement, full price, no deduction for use. Minor failure: business may choose repair. Change of mind: no guarantee right, but "if a business has a 'change of mind' policy, they must follow it."
- Consequence for wording: "deposit non-refundable" must be limited to change of mind. Add a clear carve-out: "This does not limit any rights you have under law, including Australian consumer guarantees." Do not write "all sales final" or "no refunds under any circumstance".
- Whether ACL guarantees apply to goods made and shipped from Singapore to AU for a given buyer: ACCC says overseas direct sellers must comply; practical enforcement is a lawyer question (section 3).

### 2.4 Singapore CPFTA
- [VERIFIED https://www.ccs.gov.sg/consumer-protection/legislation-and-guidelines/overview-of-the-consumer-protection--fair-trading--act/] Second Schedule lists unfair practices (example: taking payment while knowing you cannot deliver in the stated or a reasonable time). List non-exhaustive. Remedies through CASE or STB; enforcement via CCS. Page does not address overseas sellers or contract terms.
- [SNIPPET] Lemon Law is Part III of the CPFTA (non-conforming goods remedies); applies B2C only. https://case.org.sg/cpfta-lemon-law/ not fetched.
- Consequence: slot/start-month wording must stay "estimate" (already is); do not promise dates you cannot meet; if shop cannot deliver, refund (already the rule).

### 2.5 Shopee facts
- [VERIFIED] https://help.shopee.sg/portal/4/article/76733-[Order-Cancellation]-Can-I-cancel-my-order%3F : made-to-order and pre-order cancellations need seller approval; 2 calendar day response; auto-approve if no reply. Relevance: if balance is paid via Shopee listing, Shopee's own cancellation/return flow can override BKStudio terms for that payment. Needs checking with real Shopee listing settings.
- [SNIPPET] Shopee changed cancellation rules in Feb 2025; check current pages.

## 3. Suggested wording (replacement blocks)

Voice: plain, friendly, short. English. Placeholders in {BRACES} need Khai's decision.

### 3.1 FAQ (`faq/page.tsx`, replace/extend the FAQ array)

Keep existing entries 1, 3, 4, 5, 8, 9. Replace or add:

**How does the deposit work?**
Made-to-order figures need a 50% deposit when you order. We make a slot for you once the deposit is confirmed. You pay the other 50%, plus shipping, after we send you photos of the finished figure. In-stock figures are paid in full in one payment.

**What does shipping cost?** (replace, remove the TODO once settled)
Shipping is paid by you. We tell you the exact amount before you pay, and we email tracking details when your figure ships. {Carriers and typical delivery time to Singapore and Australia}.

**Do I pay customs, GST or import duty?**
Possibly. Import taxes and duties depend on your country and the value of your parcel. They are not included in our prices or shipping fee, and they are paid by you. We cannot tell you the exact amount in advance. {Khai: confirm what you declare on the customs form, and whether you add GST/duties.}

**Can I cancel my order?**
- Before you pay: your order is cancelled automatically if the payment link expires. Nothing is charged.
- After you pay a deposit on a made-to-order figure: if you change your mind, the deposit is not refunded. This is because your slot is held and work is planned for you.
- If we cancel (for example we cannot make it, a print fails, or we stop taking orders): you get your full deposit back.
- If you do not pay the balance within 30 days after we send the finished photos: the order is forfeited and we keep the deposit. We send reminders 7 days and 1 day before.
This is about changing your mind. It does not take away any rights you have under consumer law where you live, for example if a figure arrives damaged or not as described.

**What if my figure arrives damaged or not as described?** (new)
Please email us within {7} days of delivery with your order code and photos of the figure and the packaging. We will offer a repair, a replacement, or a refund, depending on the problem. Hand-painted figures can have tiny differences in colour and finish from the photos; that is part of being hand-painted and is not a fault. {Khai: confirm window and remedy.}

**What if my parcel is lost?** (new)
We ship with {tracked / insured} service and send you the tracking number. If the parcel is lost or badly delayed, contact us and we will work with the carrier. {Khai: decide who bears loss risk. Do not leave this vague.}

**Are these figures official or licensed?** (new, needs lawyer, see 4.1)
{Hold until Khai decides the IP position. Do not publish a claim such as "official" or "licensed" unless true.}

**Do you keep my details?** (optional; link to /privacy)
Only what we need to make and ship your order. We remove your name, email, phone and address 2 years after your order ends. See our privacy page.

### 3.2 Privacy (`privacy/page.tsx`, replace SECTIONS, add a contact line)

**Who we are**
BKStudio is a one-person business run by {Khai's trading name}, based in {city}, Singapore. You can reach us at {privacy contact email}. This person is our contact for privacy questions (our data protection contact).

**What we collect**
When you order: your name, email address, shipping address, optional phone number, and what you ordered. At checkout we also record your IP address, the time, and the version of the terms you agreed to, so we can show what was agreed if there is ever a dispute. Our servers also keep normal technical logs {Khai: confirm what is logged and for how long}.

**Why we use it**
To email you payment links and updates, make and ship your figure, handle problems, keep records of your agreement, and meet legal obligations. We do not use your details for marketing. {If you add a newsletter later, get separate consent.}

**Your agreement**
By placing an order you agree to us using your details for these purposes. If you want us to stop using them, tell us. We may then be unable to complete your order.

**Payments**
We never see or store your card details. You pay on Artistree or Shopee through links we email you. Those platforms have their own privacy policies.

**Who handles your data**
We share data only with services that help us run the shop: our hosting provider, our email service (Resend), the payment platform (Artistree or Shopee), the shipping carrier (who needs your name and address to deliver), and a security and delivery network (Cloudflare). Some of these providers store data outside Singapore and outside your country. We choose providers that protect data, but we do not sell your data. {Khai: confirm VPS location, whether Resend/Cloudflare are used, and whether images in R2 hold personal data.}

**How long we keep it**
Two years after your order ends, we remove your name, email address, phone number and shipping address from the order. We keep the order amount and the product for our accounts and statistics, with no personal details. {Khai: confirm if tax or accounting law requires longer; ask accountant.} Records of terms agreement, including IP address, follow the same 2 years.

**Your choices**
You can ask us to show you the personal data we hold about you, correct it, or delete it earlier. Email {privacy email}. We will reply within {30} days. We may keep what we must keep by law or for an open dispute. This applies to customers in Singapore, Australia and elsewhere.

**Keeping it safe**
We limit who can see order data (only the shop owner), use encrypted connections, and keep encrypted backups. {Align with `docs/SECURITY.md`; do not overstate.}

**If something goes wrong**
If a data breach is likely to harm you, we will tell you and the authorities where the law requires. {Khai: confirm.}

**Complaints**
Contact us first. In Singapore you can also contact the Personal Data Protection Commission (pdpc.gov.sg). In Australia you can contact the Office of the Australian Information Commissioner (oaic.gov.au). {Only keep the AU line if Khai decides to address AU readers this way; lawyer.}

**Changes**
We may update this page. The date at the top shows the latest version. Last updated: {date}.

### 3.3 Commission (`commission/page.tsx`, replace STEPS, add notes block)

Intro: "Have a character in mind that is not in the shop? Commissions are arranged through social media, not through this website."

STEPS:
1. Message us on one of the channels below with your idea, the character, the scale you want, and reference images.
2. We tell you if we can make it, the price in USD, the scale and an estimated start month. {Khai: say how long replies take.}
3. If you agree, we create a private product page just for you and send you its link. Only people with the link can see it.
4. Order from that link. Like any made-to-order figure, it needs a 50% deposit. Your slot in the monthly queue is confirmed once we receive the deposit.
5. We email progress updates. {Khai: decide if WIP photos are included and how many revision rounds.}
6. When your figure is finished we send photos. You then have 30 days to pay the remaining 50% plus shipping. We ship once it is paid.

New block "Good to know":
- Commissions follow the same rules as other made-to-order figures: the deposit is not refundable if you cancel, and is refunded in full if we cancel.
- Please agree all details (character, pose, scale, colours) before you order. {Khai: state what changes are allowed after the deposit and what they cost.}
- Dates are estimates. Printing and hand-painting can take longer than planned; we will tell you early if the start month changes.
- We make figures of characters from existing anime, games and other series as {fan-made, personal collection pieces}. {Khai/lawyer: see 4.1.}
- Your private link is a secret. Please do not share it.

### 3.4 Terms and policy (`terms_document.content_md`, new version string, e.g. 2026-11-xx)

Proposed Markdown. Each new published version gets a new `version` row (do not edit published rows, orders reference them).

```md
# Terms & policy

Version {YYYY-MM-DD}. These terms are between you and {Khai's trading name}, BKStudio ("we", "us"), a sole proprietor based in Singapore. By ticking the box at checkout you agree to them.

## 1. What we sell
- In-stock figures: finished and ready to ship.
- Made-to-order figures: printed and hand-painted for you after you order. This includes commissions arranged through social media and ordered from a private product link.
Photos are of real figures or samples. Hand-painted figures can differ slightly in colour and finish from photos. This is normal and is not a fault.

## 2. Prices and payment
- Prices are in USD and do not include shipping, import taxes, GST or duties.
- We do not take card details on this website. We email you a payment link for Artistree or Shopee. The platform's own terms apply to that payment.
- In-stock figures: pay 100% in one payment. We hold the figure for 48 hours from when we send the link.
- Made-to-order figures: pay a 50% deposit. The link expires 48 hours after we send it. You pay the remaining 50% plus shipping after we send photos of the finished figure.

## 3. Production queue and timing
We make a limited number of figures each month. Each figure takes one slot. Your place in the queue is set when we confirm your deposit, not when you place the order. The start month shown when you order is an estimate. We confirm the official month once your deposit is received. Dates are estimates, not guarantees. If we expect a delay we will tell you.

## 4. Balance payment
We send photos of the finished figure and a balance payment link. You have 30 days from that notice to pay. We send reminders 7 days and 1 day before the deadline. If the balance is not paid in time, the order is forfeited, we keep the deposit, and we may sell the figure to someone else.

## 5. Cancellation and deposit
- Before you pay anything: if the payment link expires, the order is cancelled and nothing is charged.
- You cancel after the deposit is paid: the deposit is not refunded, because we have reserved your slot and planned work for you.
- We cancel (for example we cannot make the figure, a print fails, or we stop taking orders): we refund your deposit in full.
- Commissions follow the same rules.
Refunds are returned through the payment method we used, within {N} business days. Platform fees: {Khai: say who bears them if any are non-refundable}.

## 6. Shipping, customs and delivery
- You pay shipping. We tell you the amount before you pay.
- We ship to the address you give us. Please check it. If a parcel returns because the address is wrong, reshipping costs are paid by you.
- Import taxes, GST and duties in your country are paid by you.
- Tracking number is sent by email. {Khai: risk of loss/damage in transit: who bears it, insurance.}

## 7. Damaged, faulty or not as described
Contact us within {7} days of delivery with your order code and clear photos of the figure and packaging. We will offer a repair, replacement or refund as appropriate. {Khai: confirm.}

## 8. Your legal rights
Nothing in these terms limits any right you have under law that cannot be excluded, including consumer guarantees under the Australian Consumer Law for customers in Australia and rights under Singapore consumer protection law for customers in Singapore. Where these terms and those rights conflict, your legal rights win.

## 9. Intellectual property
{Lawyer/Khai: see research file 4.1. Do not publish until decided.}
Suggested placeholders only: figures are fan-made {or licensed}. Buying a figure gives you the physical item for personal use. It does not give you the right to copy, mold, or sell copies, or any rights in the original characters, which belong to their owners.

## 10. Privacy
See the Privacy page for how we handle your personal data.

## 11. Contact and changes
Questions: {email} or the social channels on this site. We may update these terms. Each order is covered by the version you agreed to at checkout.
```

## 4. Uncertain claims and decisions (question to ask)

### 4.1 Needs lawyer
1. IP / fan art. BKStudio sells hand-painted prints of anime characters. Web sources say selling figures of recognisable characters without permission is generally infringing; "fair use" is contested; contract terms with the buyer do not bind the rights holder; platforms may delist [SNIPPET, vendor blogs, no authoritative source; one mentions a 2026 Pop Mart v. Bambu Lab suit, unverified]. Singapore/Australia have different exceptions than the US (the "fair use" concept does not map directly) [GENERAL]. Question: "Are the characters we print licensed, original designs, or fan-made? What is the minimum safe wording and approach for SG and AU sale, and for commissions of third-party characters? Does Artistree/Shopee remove such listings?"
2. ACL scope. "Do ACL consumer guarantees bind a Singapore sole proprietor shipping direct to AU consumers, and does 'non-refundable deposit on cancellation' conflict with ACL or with any unfair-contract-terms rules (ACL has unfair contract terms for standard-form consumer contracts) [GENERAL, not fetched]?"
3. Unfair terms. Forfeiting a deposit and the finished figure after 30 days: is it a reasonable cancellation charge in SG (CPFTA) and AU (penalty/UCT)? Question: "Is retaining the full 50% deposit plus reselling the figure proportionate, or should we cap/pro-rate it?"
4. Privacy applicability. Whether the AU Privacy Act applies (turnover <= AUD 3m, carries on business in AU?) and whether PDPA DPO/transfer-limitation wording is adequate [SNIPPET]. Question: "Do we need a formal DPO appointment and breach-notification procedure at our size, and is the cross-border statement enough?"
5. Retention length. Whether 2 years suits tax/accounting/dispute needs (SG records are often kept ~5 years for tax [GENERAL, not fetched]). Question to accountant: "How long must order and payment records be kept, and can personal fields be removed earlier than that?"
6. Evidence of consent. Storing the IP of the person ticking the terms is personal data; wording says why. Question: "Is IP storage justified and disclosed adequately?"

### 4.2 Needs Khai decision
1. Balance platform (Artistree vs Shopee + proxy). If Shopee: Shopee's cancellation/return flow may override site terms; made-to-order and pre-order need seller approval (verified). Question: "Which platform for the balance, and do we set the Shopee listing as pre-order?"
2. Shipping. Carrier, insurance, who bears loss/damage risk, what is declared on customs forms, and who pays import taxes. Question: "Tracked/insured? Do we claim from the carrier on lost parcels, and do we refund or reship?"
3. Damage/fault window and remedy (7 days? repair vs replace vs refund).
4. Refund timing and platform fees (who pays non-refundable Artistree/Shopee fees on a shop-cancel refund).
5. Commission revisions: free changes before painting starts? WIP photo policy? Commission slot rules if the customer is unresponsive.
6. Contact email for privacy/support and a trading name/city to publish.
7. What is logged on servers and for how long (ties to Privacy "what we collect").
8. Whether `/privacy` should mention Cloudflare/R2/Resend by name; confirm what is actually deployed at launch (VPS still not chosen per memory).
9. Do not state "no refunds" or "all sales final" anywhere; AU buyers' statutory rights cannot be excluded [VERIFIED].

### 4.3 What I could NOT verify
- Artistree's own terms, fees, refund/dispute rules, and commission rules. No official page was reachable. Everything under "Artistree" is from third-party artist ToS pages and is non-authoritative.
- PDPC and OAIC official pages (PDPC body text did not render for my fetcher; OAIC returned 403). PDPA/AU items come from search-result summaries of PDPC/OAIC material and general knowledge.
- Shopee return/refund windows for made-to-order and lost parcels (only the cancellation article was read).
- Specific wording of well-known figure shops beyond Good Smile (EU refund page read; US tariff notices as search snippets). No dedicated PVC/resin handpainted-figure sole-seller terms were fetched; independent-artist terms were snippets only.
- Current threshold and reforms for the AU small-business exemption (secondary sources mention possible change).
- CCS/CASE pages: only the CPFTA overview read; lemon law details not fetched.

## 5. URLs cited

- Shopee SG cancellation: https://help.shopee.sg/portal/4/article/76733-[Order-Cancellation]-Can-I-cancel-my-order%3F
- Shopee SG refund (search result): https://help.shopee.sg/portal/4/article/146900-[Return-Refund]-Receiving-refund
- ACCC consumer guarantees: https://www.accc.gov.au/consumers/consumer-rights-guarantees/consumer-guarantees
- ACCC repair/replace/refund: https://www.accc.gov.au/business/problem-with-a-product-or-service-you-sold/repair-replace-refund-cancel
- ACCC guide for businesses (not read): https://www.accc.gov.au/system/files/Consumer%20guarantees%20-%20A%20guide%20for%20businesses%20and%20legal%20practitioners.pdf
- OAIC small business FAQ (403): https://www.oaic.gov.au/agencies-and-organisations/faqs-for-agencies-orgs/businesses/small-business
- OAIC APP guidelines ch. B (not read): https://www.oaic.gov.au/agencies-and-organisations/app-guidelines/chapter-b-key-concepts
- PDPC obligations: https://www.pdpc.gov.sg/overview-of-pdpa/the-legislation/personal-data-protection-act/data-protection-obligations
- PDPC key concepts guideline PDF: https://www.pdpc.gov.sg/-/media/files/pdpc/pdf-files/advisory-guidelines/ag-on-key-concepts/advisory-guidelines-on-key-concepts-in-the-pdpa-17-may-2022.pdf
- CCS CPFTA overview: https://www.ccs.gov.sg/consumer-protection/legislation-and-guidelines/overview-of-the-consumer-protection--fair-trading--act/
- CASE CPFTA & Lemon Law (not read): https://case.org.sg/cpfta-lemon-law/
- Good Smile Europe refund policy: https://goodsmileeurope.com/policies/refund-policy
- Good Smile customs/shipping notices (snippet): https://www.goodsmile.com/en/news/6272 ; https://help.goodsmileus.com/support/solutions/folders/72000573864
- Example artist ToS using Artistree (snippet only): https://creamson.notion.site/Terms-of-Service-CREAMSON-COMMISSION-800dd6518c464a14805dc2f5d6e581f5 ; https://6andahalf.carrd.co
- IP overview, non-authoritative: https://all3dp.com/1/things-to-3d-print-and-sell-a-legal-guide/?critical=1
