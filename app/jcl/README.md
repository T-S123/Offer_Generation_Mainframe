<!-- Explains the local batch adapters and the additional host-specific work required for a future JCL deployment. -->
# Batch deployment boundary

The local batch program is `app/cbl/LIBAT01.cbl`; build it with `scripts/build.ps1`. Java allocates private temporary input/output files and supplies `DD_LIREQ` and `DD_LIRSP` to the GnuCOBOL process.

This profile does not run a JCL interpreter. A future z/OS job will need host-specific dataset allocation, compilation procedures and a sequential-file adapter appropriate to that host. No runnable JCL is claimed until a target environment is selected and the job is tested there. `LIUW01C` remains independent of that infrastructure through its LINKAGE copybooks.

Step 7 compiles `LIEXBAT` and its independent `LIEX01C` suppression policy. Java journals final packages and generates IBM037 fixed-block output described by `app/cpy/LIOUTREC.cpy`. The manifest supplies the actual record length; use that length when allocating a future FB dataset. The local export is a tested file interface, not a JCL job or a claim of deployment to z/OS.
