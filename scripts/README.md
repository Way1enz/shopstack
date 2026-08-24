# Run order

1. `./test.sh`
2. `docker compose up --build` (run in terminal as a prerequisite for everything below)
3. `./smoke-test.sh`
4. `./swagger.sh`
5. `./full-functional.sh`
6. `./load-balancing.sh`
7. `./idempotency.sh`
8. `./rate-limiting.sh`
9. `./circuit-breaker.sh`
10. `./observability.sh`
11. `./observability.sh --crash-recovery`
