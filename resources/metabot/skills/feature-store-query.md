---
id: feature-store-query
title: Querying the enterprise feature store
description: Answering business questions about GSM / VinFast customer spend and behaviour with query_feature_store — load to learn how to pass questions through, and how to handle clarifications and refusals.
tools: [query_feature_store]
priority: 60
---
The feature store answers business questions about GSM (rides/delivery) and VinFast (vehicle, accessories, service) customers from a catalog of approved features on a `customer_id + snapshot_date` grain, plus a precomputed cross-business-unit table.

It is not a SQL generator you drive. It is a complete pipeline that decides for itself whether it can answer, which features to use, and whether the question is safe — you hand it the question and relay what it says.

# Pass the question through verbatim

Send the user's question exactly as they wrote it, in their own language.

- Do **not** rewrite it into SQL, or into English, or into "cleaner" phrasing.
- Do **not** split a question into parts and call the tool several times.
- Do **not** add a time window, a business unit, or a filter the user did not say.

The retriever matches on the user's own wording. Paraphrasing loses the signal it needs, and a detail you helpfully add becomes a fact the answer asserts.

# The three answers

The tool returns exactly one of these. Each has one correct response.

**A result** — relay it verbatim and point the user at the card. The rows are already rendered as an interactive card in the conversation, so there is nothing to paste. Never restate, round, or recompute a number; if the answer mentions coverage below 90%, keep that in what you say.

**A clarifying question** — ask the user that exact question and stop. The question is there because a slot is genuinely missing (business unit, time window, breakdown dimension, order status) and picking wrong would produce a different answer, not a slightly worse one. Do not guess the missing detail, do not offer a default, and do not try to answer around it.

**A refusal** — say the request is out of scope and why. Refusals cover raw data and PII, business units not in the catalog, loyalty and cross-P&L questions, and write operations. A refusal is a decision, not an obstacle.

# read_resource is for explaining, not for answering

You also have `read_resource` on the feature store database. Use it only to answer questions *about* the data — which features exist, what a column means, what the grain is.

Never use it to assemble a query the tool declined to run. The catalog encodes approved joins and vetted definitions; a query you build by hand around it bypasses that, and a plausible-looking wrong number is worse than a refusal. If the tool refused, the answer is the refusal.

# Failures

If the tool reports it could not reach the service or could not validate the SQL, say so plainly. That is an outage, not a refusal — do not restate it as "this is out of scope", and do not fall back to writing the query yourself.
