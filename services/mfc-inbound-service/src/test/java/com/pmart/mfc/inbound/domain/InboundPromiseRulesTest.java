package com.pmart.mfc.inbound.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inbound.domain.InboundPromiseRules.ApprovedLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.Discrepancy;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ExpectedLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ReceiptLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ShipLine;
import com.pmart.mfc.inbound.domain.InboundPromiseRules.ViewLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class InboundPromiseRulesTest {

  static final String A = "E-00000001";
  static final String B = "E-00000002";

  static ReceiptLine line(String sku, String lot, int n, int q, int p, String reason) {
    return new ReceiptLine(sku, lot, null, n, q, p, "RCV-01", reason);
  }

  @Test
  void expectedUsesDepartureLinesBySkuAndLotOtherwiseApproved() {
    List<ApprovedLine> approved =
        List.of(new ApprovedLine(A, 60, "E"), new ApprovedLine(B, 0, "E"));
    assertThat(InboundPromiseRules.expected(approved, null))
        .containsExactly(new ExpectedLine(A, null, null, 60));
    List<ShipLine> departed =
        List.of(
            new ShipLine(A, "L1", "LPN-1", 24, "2026-12-31"),
            new ShipLine(A, "L1", "LPN-2", 24, null),
            new ShipLine(A, "L2", "LPN-3", 0, null));
    assertThat(InboundPromiseRules.expected(approved, departed))
        .containsExactly(
            new ExpectedLine(A, "L1", "2026-12-31", 48), new ExpectedLine(A, "L2", null, 0));
  }

  @Test
  void partialCancelSubtractsAndFullCancelEmpties() {
    List<ApprovedLine> approved =
        List.of(new ApprovedLine(A, 10, "E"), new ApprovedLine(B, 5, "E"));
    assertThat(InboundPromiseRules.cancel(approved, List.of(new ShipLine(A, null, null, 4, null))))
        .containsExactly(new ApprovedLine(A, 6, "E"), new ApprovedLine(B, 5, "E"));
    assertThat(InboundPromiseRules.cancel(approved, List.of())).isEmpty();
    assertThat(
            InboundPromiseRules.cancel(
                approved,
                List.of(
                    new ShipLine(A, null, null, 10, null), new ShipLine(B, null, null, 9, null))))
        .isEmpty();
  }

  @Test
  void receiptOverDepartureOrUnknownSkuOrNoLocationIsRejected() {
    List<ExpectedLine> exp = List.of(new ExpectedLine(A, "L1", null, 12));
    assertThat(InboundPromiseRules.validate(exp, List.of(line(A, "L1", 8, 2, 0, null)))).isEmpty();
    assertThat(InboundPromiseRules.validate(exp, List.of(line(A, "L1", 12, 1, 0, null))))
        .get()
        .asString()
        .contains("IV-02");
    assertThat(InboundPromiseRules.validate(exp, List.of(line(B, null, 1, 0, 0, null))))
        .isPresent();
    assertThat(
            InboundPromiseRules.validate(
                exp, List.of(new ReceiptLine(A, null, null, 1, 0, 0, " ", null))))
        .get()
        .asString()
        .contains("toLocationCode");
    assertThat(InboundPromiseRules.validate(exp, List.of(line(A, null, 0, 0, 0, null))))
        .isPresent();
  }

  @Test
  void lotIsFilledWhenSkuHasOneDepartedLot() {
    List<ExpectedLine> exp =
        List.of(new ExpectedLine(A, "L1", "2026-12-31", 12), new ExpectedLine(B, "M1", null, 3));
    List<ReceiptLine> filled =
        InboundPromiseRules.fillLots(exp, List.of(line(A, null, 10, 0, 0, null)));
    assertThat(filled.getFirst().lotId()).isEqualTo("L1");
    assertThat(filled.getFirst().expiryDate()).isEqualTo("2026-12-31");
  }

  @Test
  void discrepancyPrefersGivenQuantityReasonElseShortOrNotReceived() {
    List<ExpectedLine> exp =
        List.of(new ExpectedLine(A, "L1", null, 12), new ExpectedLine(B, null, null, 3));
    List<Discrepancy> d =
        InboundPromiseRules.discrepancies(exp, List.of(line(A, "L1", 8, 2, 0, "DAMAGED")));
    assertThat(d)
        .containsExactly(
            new Discrepancy(A, 12, 10, "SHORT"), new Discrepancy(B, 3, 0, "NOT_RECEIVED"));
    assertThat(
            InboundPromiseRules.discrepancies(
                List.of(new ExpectedLine(A, "L1", null, 12)),
                List.of(line(A, "L1", 10, 2, 0, "DAMAGED"))))
        .isEmpty();
  }

  @Test
  void viewAddsAppliedReceiptToMatchingLot() {
    List<ExpectedLine> exp =
        List.of(new ExpectedLine(A, "L1", null, 12), new ExpectedLine(A, "L2", null, 4));
    List<ViewLine> v =
        InboundPromiseRules.view(
            exp, List.of(line(A, "L2", 3, 1, 0, null), line(A, "X", 5, 0, 1, null)));
    assertThat(v.get(0).receivedQty()).isEqualTo(6);
    assertThat(v.get(0).pendingQty()).isEqualTo(1);
    assertThat(v.get(1).receivedQty()).isEqualTo(4);
    assertThat(v.get(1).quarantinedQty()).isEqualTo(1);
  }
}
