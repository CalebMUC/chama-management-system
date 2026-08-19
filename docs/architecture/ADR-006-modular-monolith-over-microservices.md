# ADR-006: Modular Monolith over Microservices

**Status:** Accepted  
**Date:** 2025-01  
**Author:** Caleb Muchiri

## Context

The system was initially designed as 11 microservices. Before
implementation began in earnest, a hosting cost analysis was done.

## Cost Comparison

| Approach | Monthly Cost | Break-even Chamas |
|---|---|---|
| 11 Microservices | ~KES 25,000 | 17 chamas on Starter plan |
| Modular Monolith | ~KES 2,500 | 2 chamas on Starter plan |

## Decision

Implement as a modular monolith with microservice-ready module
boundaries. Extract services only when a specific module needs
independent scaling or when recurring revenue justifies the cost.

## Module Boundary Rules

1. Modules never import each other's repositories
2. Cross-module calls go through service interfaces
3. Events use Spring ApplicationEventPublisher (swap to RabbitMQ on extraction)
4. Each module owns its own database tables logically

## Extraction Triggers

- Payment module → when PCI compliance audit is required
- Notification module → when SMS volume exceeds 100k/month
- Loan module → when loan portfolio exceeds 1,000 active loans

## Consequences

- Simpler deployment and debugging
- Lower hosting cost at early stage
- Slightly higher coupling risk — mitigated by strict module boundary rules
- Microservices architecture remains documented for future reference