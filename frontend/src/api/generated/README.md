# Generated API contract

`schema.ts` is generated from the backend Springdoc document. Do not edit it
manually.

With the backend running locally, refresh the contract with:

```powershell
npm run api:generate
```

To verify that the checked-in generated file matches the current backend
contract:

```powershell
npm run api:check
```

The compatibility types consumed by the Vue application live in
`../types.ts`. UI-only state types may remain there, but API DTOs should be
based on the generated `components["schemas"]` definitions.
