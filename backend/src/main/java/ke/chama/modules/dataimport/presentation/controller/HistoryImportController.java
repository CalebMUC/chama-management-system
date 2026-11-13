package ke.chama.modules.dataimport.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ke.chama.modules.dataimport.application.port.in.HistoryImports;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.BatchView;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.Kind;
import ke.chama.modules.dataimport.application.port.in.HistoryImports.PreviewView;
import ke.chama.modules.dataimport.domain.exception.ImportExceptions.InvalidImportFileException;
import ke.chama.shared.security.ChamaPermission;
import ke.chama.shared.security.CurrentUser;
import ke.chama.shared.security.RequiresChamaPermission;
import ke.chama.shared.tenancy.TenantContext;
import ke.chama.shared.web.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Bringing a chama's history over from Excel, one file per kind. Chairperson by default
 * (CHAMA_SETTINGS_MANAGE). {kind} is setup, contributions, loans, penalties, investments,
 * expenses or reconciliation.
 * <pre>
 * GET  /api/v1/imports/history/{kind}/template     the .xlsx template
 * POST /api/v1/imports/history/{kind}/preview      multipart: file - checks every row, saves nothing
 * POST /api/v1/imports/history/{kind}              multipart: file - imports it as one batch
 * GET  /api/v1/imports/history/batches             every batch, newest first
 * POST /api/v1/imports/history/batches/{id}/undo   undoes the latest batch
 * </pre>
 */
@Tag(name = "Imports", description = "Bring a chama's history over from Excel")
@RestController
@RequestMapping("/api/v1/imports/history")
class HistoryImportController {

    private final HistoryImports imports;

    HistoryImportController(HistoryImports imports) {
        this.imports = imports;
    }

    @Operation(summary = "Download a template",
            description = "An .xlsx workbook with instructions, the sheets to fill in and the chama's members and products.")
    @GetMapping("/{kind}/template")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ResponseEntity<byte[]> template(@PathVariable String kind) {
        Kind k = kind(kind);
        return ResponseEntity.ok()
                .contentType(OpeningBalanceImportController.XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("history-" + (k.ordinal() + 1) + "-" + k.slug() + "-template.xlsx").build().toString())
                .body(imports.template(TenantContext.requireChama(), k));
    }

    @Operation(summary = "Preview a file",
            description = "Checks every row and lists each problem with its sheet, row and column, with the totals "
                    + "that would be imported. Nothing is saved.")
    @PostMapping(path = "/{kind}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<PreviewView> preview(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
        PreviewView preview = imports.preview(TenantContext.requireChama(), kind(kind), bytes(file));
        return ApiResponse.ok(preview, preview.importable() ? "Ready to import"
                : preview.errors() + " problem" + (preview.errors() == 1 ? "" : "s") + " to fix");
    }

    @Operation(summary = "Import a file", description = "Imports every row as one batch, all or nothing. No emails are sent.")
    @PostMapping(path = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<BatchView> importFile(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(imports.importFile(TenantContext.requireChama(), CurrentUser.id(), kind(kind),
                file.getOriginalFilename(), bytes(file)), "Imported");
    }

    @Operation(summary = "Every import, newest first")
    @GetMapping("/batches")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<List<BatchView>> batches() {
        return ApiResponse.ok(imports.batches(TenantContext.requireChama()));
    }

    @Operation(summary = "Undo the latest import",
            description = "Removes what it created and reverses its ledger entries (dated as the originals).")
    @PostMapping("/batches/{id}/undo")
    @RequiresChamaPermission(ChamaPermission.CHAMA_SETTINGS_MANAGE)
    ApiResponse<BatchView> undo(@PathVariable UUID id) {
        return ApiResponse.ok(imports.undo(TenantContext.requireChama(), CurrentUser.id(), id), "Import undone");
    }

    private static Kind kind(String slug) {
        try {
            return Kind.fromSlug(slug);
        } catch (IllegalArgumentException e) {
            throw new InvalidImportFileException("Unknown import " + slug + ": use setup, contributions, loans, "
                    + "penalties, investments, expenses or reconciliation");
        }
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidImportFileException("The file could not be uploaded");
        }
    }
}
