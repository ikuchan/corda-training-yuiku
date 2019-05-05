package net.corda.training.contract

import net.corda.core.contracts.*
import net.corda.core.contracts.Requirements.using
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.utils.sumCash
import net.corda.training.state.IOUState

/**
 * This is where you'll add the contract code which defines how the [IOUState] behaves. Look at the unit tests in
 * [IOUContractTests] for instructions on how to complete the [IOUContract] class.
 */
class IOUContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "net.corda.training.contract.IOUContract"
    }

    /**
     * Add any commands required for this contract as classes within this interface.
     * It is useful to encapsulate your commands inside an interface, so you can use the [requireSingleCommand]
     * function to check for a number of commands which implement this interface.
     */
    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Transfer : TypeOnlyCommandData(), Commands
        class Settle : TypeOnlyCommandData(), Commands
    }
    /**
     * The contract code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    override fun verify(tx: LedgerTransaction) {
        requireThat {
            val command = tx.commands.requireSingleCommand<Commands>()
            when (command.value){
                is Commands.Issue -> requireThat {
                    "No inputs should be consumed when issuing an IOU." using(tx.inputs.isEmpty())

                    "Only one output state should be created when issuing an IOU." using(tx.outputs.size == 1)
                    val iou = tx.outputStates.first() as IOUState

                    "A newly issued IOU must have a positive amount." using( iou.amount > Amount(0,iou.amount.token))

                    "The lender and borrower cannot have the same identity." using(
                            iou.borrower != iou.lender
                            )

                    "Both lender and borrower together only may sign IOU issue transaction." using(

                            command.signers.toSet() == iou.participants.map{it.owningKey}.toSet()
                            )
                }
                is Commands.Transfer -> requireThat{
                    "An IOU transfer transaction should only consume one input state." using(tx.inputs.size == 1)

                    "An IOU transfer transaction should only create one output state." using(tx.outputs.size ==1)

                    val outputIou = tx.outputStates.first() as IOUState
                    val inputIou = tx.inputStates.first() as IOUState

                    "Only the lender property may change." using (outputIou.borrower == inputIou.borrower)
                    "Only the lender property may change." using (inputIou == outputIou.withNewLender(inputIou.lender))
                    "The lender property must change in a transfer." using( outputIou.lender != inputIou.lender)

                    "The borrower, old lender and new lender only must sign an IOU transfer transaction" using(
                            command.signers.toSet() ==
                                    (outputIou.participants.map{it.owningKey}.toSet())
                                    union
                                    (inputIou.participants.map{it.owningKey}.toSet())
                            )

                }
                is Commands.Settle -> requireThat{
                    val ious = tx.groupStates<IOUState, UniqueIdentifier> {it.linearId  }.single()
                    "There must be one input IOU." using( ious.inputs.size == 1)
                    val cashs = tx.outputsOfType<Cash.State>()
                    "There must be output cash." using( cashs.isNotEmpty())
                    "There must be output cash paid to the recipient." using( cashs.filter{it.owner == ious.inputs.single().lender}.isNotEmpty())
                    val inputIou = ious.inputs.single()
                    val amountOutstanding = inputIou.amount - inputIou.paid
                    val acceptableCash = cashs.filter { it.owner == inputIou.lender }
                    val sumAcceptableCash = acceptableCash.sumCash().withoutIssuer()
                    "The amount settled cannot be more than the amount outstanding." using(amountOutstanding >= sumAcceptableCash)
                    "Token mismatch: GBP vs USD" using(amountOutstanding.token == sumAcceptableCash.token)

                    if(amountOutstanding == sumAcceptableCash){
                        "There must be no output IOU as it has been fully settled." using(ious.outputs.size ==0)

                    }else {
                        "There must be one output IOU." using (ious.outputs.size == 1)
                        "The borrower may not change when settling." using(ious.inputs.single().borrower == ious.outputs.single().borrower)
                        "The amount may not change when settling." using (ious.inputs.single().amount == ious.outputs.single().amount)
                        "The lender may not change when settling." using(ious.inputs.single().lender == ious.outputs.single().lender)

                    }

                    "Both lender and borrower together only must sign IOU settle transaction." using(
                            command.signers.toSet() == inputIou.participants.map{it.owningKey}.toSet()
                            )
                }
            }


        }
    }
}