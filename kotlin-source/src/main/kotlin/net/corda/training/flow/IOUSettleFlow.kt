package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.workflows.asset.CashUtils
import net.corda.finance.workflows.getCashBalance
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.util.*

/**
 * This is the flow which handles the (partial) settlement of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled vy the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUSettleFlow(val linearId: UniqueIdentifier, val amount: Amount<Currency>): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {

        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val iouToSettle = serviceHub.vaultService.queryBy<IOUState>(queryCriteria).states.single()
        val counterparty = iouToSettle.state.data.lender
        if ( ourIdentity != iouToSettle.state.data.borrower){
            throw IllegalArgumentException("IOU settlement flow must be initiated by the borrower.")
        }
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val builder = TransactionBuilder(notary = notary)

        val cashBalance = serviceHub.getCashBalance(amount.token)

        if (cashBalance < amount) {
            throw IllegalArgumentException("Borrower has only $cashBalance but needs $amount to settle.")
        } else if (amount > (iouToSettle.state.data.amount - iouToSettle.state.data.paid)) {
            throw IllegalArgumentException("Borrower tried to settle with $amount but only needs ${ (iouToSettle.state.data.amount - iouToSettle.state.data.paid) }")
        }
        val (_, cashKeys) = CashUtils.generateSpend(serviceHub, builder, amount, ourIdentityAndCert, counterparty)


        val settleCommand = Command(IOUContract.Commands.Settle(),listOf(ourIdentity.owningKey,counterparty.owningKey))

        builder.withItems(iouToSettle,settleCommand)
        val amountRemaining = iouToSettle.state.data.amount - iouToSettle.state.data.paid - amount
        if (amountRemaining > Amount(0, amount.token)) {
            val settledIOU: IOUState = iouToSettle.state.data.pay(amount)
            builder.addOutputState(settledIOU, IOUContract.IOU_CONTRACT_ID)
        }


        builder.verify(serviceHub)

        val myKeysToSign = (cashKeys.toSet() + ourIdentity.owningKey).toList()
        val signedTx = serviceHub.signInitialTransaction(builder, myKeysToSign)

        val counterPartySession = initiateFlow(counterparty)
        subFlow(IdentitySyncFlow.Send(counterPartySession, signedTx.tx))

        //val stx = subFlow(CollectSignaturesFlow(signedTx, listOf(initiateFlow(counterparty))))
        val stx = subFlow(CollectSignaturesFlow(signedTx, listOf(counterPartySession), myOptionalKeys = myKeysToSign))

        return subFlow(FinalityFlow(stx, counterPartySession))

    }
}

/**
 * This is the flow which signs IOU settlements.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUSettleFlow::class)
class IOUSettleFlowResponder(val flowSession: FlowSession): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call() :SignedTransaction{
        subFlow(IdentitySyncFlow.Receive(flowSession))

        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) {
            }
        }

        val txWeJustSignedId = subFlow(signedTransactionFlow)

        return subFlow(ReceiveFinalityFlow(otherSideSession = flowSession, expectedTxId = txWeJustSignedId.id))
    }
}

@InitiatingFlow
@StartableByRPC
/**
 * Self issues the calling node an amount of cash in the desired currency.
 * Only used for demo/sample/training purposes!
 */
class SelfIssueCashFlow(val amount: Amount<Currency>) : FlowLogic<Cash.State>() {
    @Suspendable
    override fun call(): Cash.State {
        /** Create the cash issue command. */
        val issueRef = OpaqueBytes.of(0)
        /** Note: ongoing work to support multiple notary identities is still in progress. */
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        /** Create the cash issuance transaction. */
        val cashIssueTransaction = subFlow(CashIssueFlow(amount, issueRef, notary))
        /** Return the cash output. */
        return cashIssueTransaction.stx.tx.outputs.single().data as Cash.State
    }
}